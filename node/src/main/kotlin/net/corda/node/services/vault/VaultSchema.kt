package net.corda.node.services.vault

import net.corda.core.node.services.Vault
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import java.time.Instant
import java.util.*
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Index
import javax.persistence.Table

/**
 * JPA representation of the core Vault Schema
 */
object VaultSchema

/**
 * First version of the Vault ORM schema
 */
object VaultSchemaV1 : MappedSchema(schemaFamily = VaultSchema.javaClass, version = 1, mappedTypes = listOf(VaultStates::class.java)) {
    @Entity
    @Table(name = "cash_states",
            indexes = arrayOf(Index(name = "state_status_idx", columnList = "state_status")))
    class VaultStates(

            /** refers to the notary a state is attached to */
            @Column(name = "notary_name")
            var notaryName: String,

            @Column(name = "notary_key", length = 65535) // TODO What is the upper limit on size of CompositeKey?
            var notaryKey: String,

            /** references a concrete ContractState that is [QueryableState] and has a [MappedSchema] */
            @Column(name = "contract_state_class_name")
            var contractStateClassName: String,

            /** refers to serialized transaction Contract State */
            // TODO: define contract state size maximum size and adjust length accordingly
            @Column(name = "contract_state", length = 100000)
            var contractState: ByteArray,

            /** state lifecycle: unconsumed, consumed */
            @Column(name = "state_status")
            var stateStatus: Vault.StateStatus,

            /** refers to timestamp recorded upon entering UNCONSUMED state */
            @Column(name = "recorded_timestamp")
            var recordedTime: Instant,

            /** refers to timestamp recorded upon entering CONSUMED state */
            @Column(name = "consumed_timestamp", nullable = true)
            var consumedTime: Instant?,

            /** used to denote a state has been soft locked (to prevent double spend)
             *  will contain a temporary unique [UUID] obtained from a flow session */
            @Column(name = "lock_id", nullable = true)
            var lockId: String,

            /** refers to the last time a lock was taken (reserved) or updated (released, re-reserved) */
            @Column(name = "lock_timestamp", nullable = true)
            var lockUpdateTime: Instant?

    ) : PersistentState()
}