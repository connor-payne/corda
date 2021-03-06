package net.corda.node.services.statemachine.transitions

import net.corda.core.crypto.SecureHash
import net.corda.core.flows.InitiatingFlow
import net.corda.core.internal.FlowIORequest
import net.corda.core.serialization.deserialize
import net.corda.core.utilities.Try
import net.corda.node.services.messaging.DeduplicationHandler
import net.corda.node.services.statemachine.Action
import net.corda.node.services.statemachine.Checkpoint
import net.corda.node.services.statemachine.DeduplicationId
import net.corda.node.services.statemachine.EndSessionMessage
import net.corda.node.services.statemachine.ErrorState
import net.corda.node.services.statemachine.Event
import net.corda.node.services.statemachine.ExistingSessionMessage
import net.corda.node.services.statemachine.ExternalEvent
import net.corda.node.services.statemachine.FlowRemovalReason
import net.corda.node.services.statemachine.FlowSessionImpl
import net.corda.node.services.statemachine.FlowState
import net.corda.node.services.statemachine.InitialSessionMessage
import net.corda.node.services.statemachine.SenderDeduplicationId
import net.corda.node.services.statemachine.SessionId
import net.corda.node.services.statemachine.SessionMessage
import net.corda.node.services.statemachine.SessionState
import net.corda.node.services.statemachine.StateMachineState
import net.corda.node.services.statemachine.SubFlow

/**
 * This is the top level event-handling transition function capable of handling any [Event].
 *
 * It is a *pure* function taking a state machine state and an event, returning the next state along with a list of IO
 * actions to execute.
 */
class TopLevelTransition(
        override val context: TransitionContext,
        override val startingState: StateMachineState,
        val event: Event
) : Transition {

    @Suppress("ComplexMethod")
    override fun transition(): TransitionResult {

        if (startingState.isKilled) {
            return KilledFlowTransition(context, startingState, event).transition()
        }

        return when (event) {
            is Event.DoRemainingWork -> DoRemainingWorkTransition(context, startingState).transition()
            is Event.DeliverSessionMessage -> DeliverSessionMessageTransition(context, startingState, event).transition()
            is Event.Error -> errorTransition(event)
            is Event.TransactionCommitted -> transactionCommittedTransition(event)
            is Event.SoftShutdown -> softShutdownTransition()
            is Event.StartErrorPropagation -> startErrorPropagationTransition()
            is Event.EnterSubFlow -> enterSubFlowTransition(event)
            is Event.LeaveSubFlow -> leaveSubFlowTransition()
            is Event.Suspend -> suspendTransition(event)
            is Event.FlowFinish -> flowFinishTransition(event)
            is Event.InitiateFlow -> initiateFlowTransition(event)
            is Event.AsyncOperationCompletion -> asyncOperationCompletionTransition(event)
            is Event.AsyncOperationThrows -> asyncOperationThrowsTransition(event)
            is Event.RetryFlowFromSafePoint -> retryFlowFromSafePointTransition()
            is Event.ReloadFlowFromCheckpointAfterSuspend -> reloadFlowFromCheckpointAfterSuspendTransition()
            is Event.OvernightObservation -> overnightObservationTransition()
            is Event.WakeUpFromSleep -> wakeUpFromSleepTransition()
            is Event.TerminateSessions -> terminateSessionsTransition(event)
        }
    }

    private fun errorTransition(event: Event.Error): TransitionResult {
        return builder {
            freshErrorTransition(event.exception, event.rollback)
            FlowContinuation.ProcessEvents
        }
    }

    private fun transactionCommittedTransition(event: Event.TransactionCommitted): TransitionResult {
        return builder {
            val checkpoint = currentState.checkpoint
            if (isWaitingForLedgerCommit(currentState, checkpoint, event.transaction.id)) {
                currentState = currentState.copy(isWaitingForFuture = false)
                if (isErrored()) {
                    return@builder FlowContinuation.ProcessEvents
                }
                resumeFlowLogic(event.transaction)
            } else {
                freshErrorTransition(UnexpectedEventInState())
                FlowContinuation.ProcessEvents
            }
        }
    }

    private fun isWaitingForLedgerCommit(
        currentState: StateMachineState,
        checkpoint: Checkpoint,
        transactionId: SecureHash
    ): Boolean {
        return currentState.isWaitingForFuture &&
                checkpoint.flowState is FlowState.Started &&
                checkpoint.flowState.flowIORequest is FlowIORequest.WaitForLedgerCommit &&
                checkpoint.flowState.flowIORequest.hash == transactionId
    }

    private fun softShutdownTransition(): TransitionResult {
        val lastState = startingState.copy(isRemoved = true)
        return TransitionResult(
                newState = lastState,
                actions = listOf(
                        Action.RemoveSessionBindings(startingState.checkpoint.checkpointState.sessions.keys),
                        Action.RemoveFlow(context.id, FlowRemovalReason.SoftShutdown, lastState)
                ),
                continuation = FlowContinuation.Abort
        )
    }

    private fun startErrorPropagationTransition(): TransitionResult {
        return builder {
            val errorState = currentState.checkpoint.errorState
            when (errorState) {
                ErrorState.Clean -> freshErrorTransition(UnexpectedEventInState())
                is ErrorState.Errored -> {
                    currentState = currentState.copy(
                            checkpoint = currentState.checkpoint.copy(
                                    errorState = errorState.copy(propagating = true)
                            )
                    )
                    actions.add(Action.ScheduleEvent(Event.DoRemainingWork))
                }
            }
            FlowContinuation.ProcessEvents
        }
    }

    private fun enterSubFlowTransition(event: Event.EnterSubFlow): TransitionResult {
        return builder {
            val subFlow = SubFlow.create(event.subFlowClass, event.subFlowVersion, event.isEnabledTimedFlow)
            when (subFlow) {
                is Try.Success -> {
                    val containsTimedSubflow = containsTimedFlows(currentState.checkpoint.checkpointState.subFlowStack)
                    currentState = currentState.copy(
                            checkpoint = currentState.checkpoint.addSubflow(subFlow.value)
                    )
                    // We don't schedule a timeout if there already is a timed subflow on the stack - a timeout had
                    // been scheduled already.
                    if (event.isEnabledTimedFlow && !containsTimedSubflow) {
                        actions.add(Action.ScheduleFlowTimeout(currentState.flowLogic.runId))
                    }
                }
                is Try.Failure -> {
                    freshErrorTransition(subFlow.exception)
                }
            }
            FlowContinuation.ProcessEvents
        }
    }

    private fun leaveSubFlowTransition(): TransitionResult {
        return builder {
            val checkpoint = currentState.checkpoint
            if (checkpoint.checkpointState.subFlowStack.isEmpty()) {
                freshErrorTransition(UnexpectedEventInState())
            } else {
                val isLastSubFlowTimed = checkpoint.checkpointState.subFlowStack.last().isEnabledTimedFlow
                val newSubFlowStack = checkpoint.checkpointState.subFlowStack.dropLast(1)
                currentState = currentState.copy(
                        checkpoint = checkpoint.setSubflows(newSubFlowStack)
                )
                if (isLastSubFlowTimed && !containsTimedFlows(currentState.checkpoint.checkpointState.subFlowStack)) {
                    actions.add(Action.CancelFlowTimeout(currentState.flowLogic.runId))
                }
            }
            FlowContinuation.ProcessEvents
        }
    }

    private fun containsTimedFlows(subFlowStack: List<SubFlow>): Boolean {
        return subFlowStack.any { it.isEnabledTimedFlow }
    }

    private fun suspendTransition(event: Event.Suspend): TransitionResult {
        return builder {
            val newCheckpoint = currentState.checkpoint.run {
                val newCheckpointState = if (checkpointState.invocationContext.arguments.isNotEmpty()) {
                    checkpointState.copy(
                        invocationContext = checkpointState.invocationContext.copy(arguments = emptyList()),
                        numberOfSuspends = checkpointState.numberOfSuspends + 1
                    )
                } else {
                    checkpointState.copy(numberOfSuspends = checkpointState.numberOfSuspends + 1)
                }
                copy(
                    flowState = FlowState.Started(event.ioRequest, event.fiber),
                    checkpointState = newCheckpointState,
                    flowIoRequest = event.ioRequest::class.java.simpleName,
                    progressStep = event.progressStep?.label
                )
            }
            if (event.maySkipCheckpoint) {
                actions.addAll(arrayOf(
                        Action.CommitTransaction,
                        Action.ScheduleEvent(Event.DoRemainingWork)
                ))
                currentState = currentState.copy(
                    checkpoint = newCheckpoint,
                    isFlowResumed = false
                )
            } else {
                actions.addAll(arrayOf(
                        Action.PersistCheckpoint(context.id, newCheckpoint, isCheckpointUpdate = currentState.isAnyCheckpointPersisted),
                        Action.PersistDeduplicationFacts(currentState.pendingDeduplicationHandlers),
                        Action.CommitTransaction,
                        Action.AcknowledgeMessages(currentState.pendingDeduplicationHandlers),
                        Action.ScheduleEvent(Event.DoRemainingWork)
                ))
                currentState = currentState.copy(
                    checkpoint = newCheckpoint,
                    pendingDeduplicationHandlers = emptyList(),
                    isFlowResumed = false,
                    isAnyCheckpointPersisted = true
                )
            }
            FlowContinuation.ProcessEvents
        }
    }

    private fun flowFinishTransition(event: Event.FlowFinish): TransitionResult {
        return builder {
            val checkpoint = currentState.checkpoint
            when (checkpoint.errorState) {
                ErrorState.Clean -> {
                    val pendingDeduplicationHandlers = currentState.pendingDeduplicationHandlers
                    currentState = currentState.copy(
                            checkpoint = checkpoint.copy(
                                checkpointState = checkpoint.checkpointState.copy(
                                        numberOfSuspends = checkpoint.checkpointState.numberOfSuspends + 1
                                ),
                                flowState = FlowState.Completed,
                                result = event.returnValue,
                                status = Checkpoint.FlowStatus.COMPLETED
                            ),
                            pendingDeduplicationHandlers = emptyList(),
                            isFlowResumed = false,
                            isRemoved = true
                    )
                    val allSourceSessionIds = checkpoint.checkpointState.sessions.keys
                    if (currentState.isAnyCheckpointPersisted) {
                        actions.add(Action.RemoveCheckpoint(context.id))
                    }
                    actions.addAll(arrayOf(
                        Action.PersistDeduplicationFacts(pendingDeduplicationHandlers),
                            Action.ReleaseSoftLocks(event.softLocksId),
                            Action.CommitTransaction,
                            Action.AcknowledgeMessages(pendingDeduplicationHandlers),
                            Action.RemoveSessionBindings(allSourceSessionIds),
                            Action.RemoveFlow(context.id, FlowRemovalReason.OrderlyFinish(event.returnValue), currentState)
                    ))
                    sendEndMessages()
                    // Resume to end fiber
                    FlowContinuation.Resume(null)
                }
                is ErrorState.Errored -> {
                    currentState = currentState.copy(isFlowResumed = false)
                    actions.add(Action.RollbackTransaction)
                    FlowContinuation.ProcessEvents
                }
            }
        }
    }

    private fun TransitionBuilder.sendEndMessages() {
        val sendEndMessageActions = currentState.checkpoint.checkpointState.sessions.values.mapIndexed { index, state ->
            if (state is SessionState.Initiated) {
                val message = ExistingSessionMessage(state.peerSinkSessionId, EndSessionMessage)
                val deduplicationId = DeduplicationId.createForNormal(currentState.checkpoint, index, state)
                Action.SendExisting(state.peerParty, message, SenderDeduplicationId(deduplicationId, currentState.senderUUID))
            } else {
                null
            }
        }.filterNotNull()
        actions.addAll(sendEndMessageActions)
    }

    private fun initiateFlowTransition(event: Event.InitiateFlow): TransitionResult {
        return builder {
            val checkpoint = currentState.checkpoint
            val initiatingSubFlow = getClosestAncestorInitiatingSubFlow(checkpoint)
            if (initiatingSubFlow == null) {
                freshErrorTransition(IllegalStateException("Tried to initiate in a flow not annotated with @${InitiatingFlow::class.java.simpleName}"))
                return@builder FlowContinuation.ProcessEvents
            }
            val sourceSessionId = SessionId.createRandom(context.secureRandom)
            val sessionImpl = FlowSessionImpl(event.destination, event.wellKnownParty, sourceSessionId)
            val newSessions = checkpoint.checkpointState.sessions + (sourceSessionId to SessionState.Uninitiated(event.destination, initiatingSubFlow, sourceSessionId, context.secureRandom.nextLong()))
            currentState = currentState.copy(checkpoint = checkpoint.setSessions(newSessions))
            actions.add(Action.AddSessionBinding(context.id, sourceSessionId))
            FlowContinuation.Resume(sessionImpl)
        }
    }

    private fun getClosestAncestorInitiatingSubFlow(checkpoint: Checkpoint): SubFlow.Initiating? {
        for (subFlow in checkpoint.checkpointState.subFlowStack.asReversed()) {
            if (subFlow is SubFlow.Initiating) {
                return subFlow
            }
        }
        return null
    }

    private fun asyncOperationCompletionTransition(event: Event.AsyncOperationCompletion): TransitionResult {
        return builder {
            resumeFlowLogic(event.returnValue)
        }
    }

    private fun asyncOperationThrowsTransition(event: Event.AsyncOperationThrows): TransitionResult {
        return builder {
            resumeFlowLogic(event.throwable)
        }
    }

    private fun retryFlowFromSafePointTransition(): TransitionResult {
        return builder {
            // Need to create a flow from the prior checkpoint or flow initiation.
            actions.add(Action.RetryFlowFromSafePoint(currentState))
            FlowContinuation.Abort
        }
    }

    private fun reloadFlowFromCheckpointAfterSuspendTransition(): TransitionResult {
        return builder {
            currentState = currentState.copy(reloadCheckpointAfterSuspendCount = currentState.reloadCheckpointAfterSuspendCount!! + 1)
            actions.add(Action.RetryFlowFromSafePoint(currentState))
            FlowContinuation.Abort
        }
    }

    private fun overnightObservationTransition(): TransitionResult {
        return builder {
            val flowStartEvents = currentState.pendingDeduplicationHandlers.filter(::isFlowStartEvent)
            val newCheckpoint = startingState.checkpoint.copy(status = Checkpoint.FlowStatus.HOSPITALIZED)
            actions += Action.CreateTransaction
            actions += Action.PersistDeduplicationFacts(flowStartEvents)
            actions += Action.PersistCheckpoint(context.id, newCheckpoint, isCheckpointUpdate = currentState.isAnyCheckpointPersisted)
            actions += Action.CommitTransaction
            actions += Action.AcknowledgeMessages(flowStartEvents)
            currentState = currentState.copy(
                checkpoint = startingState.checkpoint.copy(status = Checkpoint.FlowStatus.HOSPITALIZED),
                pendingDeduplicationHandlers = currentState.pendingDeduplicationHandlers - flowStartEvents
            )
            FlowContinuation.ProcessEvents
        }
    }

    private fun isFlowStartEvent(handler: DeduplicationHandler): Boolean {
        return handler.externalCause.run { isSessionInit() || isFlowStart() }
    }

    private fun ExternalEvent.isSessionInit(): Boolean {
        return this is ExternalEvent.ExternalMessageEvent && this.receivedMessage.data.deserialize<SessionMessage>() is InitialSessionMessage
    }

    private fun ExternalEvent.isFlowStart(): Boolean {
        return this is ExternalEvent.ExternalStartFlowEvent<*>
    }

    private fun wakeUpFromSleepTransition(): TransitionResult {
        return builder {
            resumeFlowLogic(Unit)
        }
    }

    private fun terminateSessionsTransition(event: Event.TerminateSessions): TransitionResult {
        return builder {
            val sessions = event.sessions
            val newCheckpoint = currentState.checkpoint
                .removeSessions(sessions)
                .removeSessionsToBeClosed(sessions)
            currentState = currentState.copy(checkpoint = newCheckpoint)
            actions.add(Action.RemoveSessionBindings(sessions))
            FlowContinuation.ProcessEvents
        }
    }
}
