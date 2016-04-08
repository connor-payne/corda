package contracts

import core.*
import core.crypto.SecureHash

// The dummy contract doesn't do anything useful. It exists for testing purposes.

val DUMMY_PROGRAM_ID = DummyContract()

class DummyContract : Contract {
    class State(val magicNumber: Int = 0) : ContractState {
        override val contract = DUMMY_PROGRAM_ID
    }

    interface Commands : CommandData {
        class Create : TypeOnlyCommandData(), Commands
    }

    override fun verify(tx: TransactionForVerification) {
        // Always accepts.
    }

    // The "empty contract"
    override val legalContractReference: SecureHash = SecureHash.sha256("")

    fun generateInitial(owner: PartyReference, magicNumber: Int) : TransactionBuilder {
        val state = State(magicNumber)
        return TransactionBuilder().withItems( state, Command(Commands.Create(), owner.party.owningKey) )
    }
}