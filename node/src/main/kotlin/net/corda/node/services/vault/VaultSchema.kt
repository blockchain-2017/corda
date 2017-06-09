package net.corda.node.services.vault.schemas.jpa

import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.toBase58String
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.node.services.Vault
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.serialization.OpaqueBytes
import net.corda.node.services.vault.schemas.jpa.CommonSchemaV1.LinearState
import java.security.PublicKey
import java.time.Instant
import java.util.*
import javax.persistence.*

/**
 * JPA representation of the core Vault Schema
 */
object VaultSchema

/**
 * First version of the Vault ORM schema
 */
object VaultSchemaV1 : MappedSchema(schemaFamily = VaultSchema.javaClass, version = 1, mappedTypes = listOf(VaultStates::class.java, VaultLinearStates::class.java, VaultFungibleStates::class.java,  CommonSchemaV1.Party::class.java)) {
    @Entity
    @Table(name = "vault_states",
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

    @Entity
    @Table(name = "vault_linear_states",
            indexes = arrayOf(Index(name = "external_id_index", columnList = "external_id"),
                              Index(name = "uuid_index", columnList = "uuid"),
                                Index(name = "deal_reference_idx", columnList = "deal_reference")))
    class VaultLinearStates(
            /**
             *  Represents a [LinearState] [UniqueIdentifier]
             */
            @Column(name = "external_id")
            var externalId: String?,     // TODO: Generics prevent using a Nullable type
//            var externalId: String,

            @Column(name = "uuid", nullable = false)
            var uuid: UUID,

            // TODO: DealState to be deprecated (collapsed into LinearState)

            /** Deal State attributes **/
            @Column(name = "deal_reference")
            var dealReference: String,

            @OneToMany(cascade = arrayOf(CascadeType.PERSIST))
//            @JoinColumn(name = "party_id")
            var dealParties: Set<CommonSchemaV1.Party>

    ) : PersistentState() {
        constructor(uid: UniqueIdentifier) : this(externalId = uid.externalId, uuid = uid.id, dealReference = "", dealParties = setOf())
        constructor(uid: UniqueIdentifier, _dealReference: String, _dealParties: List<AbstractParty>) : this(externalId = uid.externalId ?: "", uuid = uid.id,
                dealReference = _dealReference, dealParties = _dealParties.map{ CommonSchemaV1.Party(it) }.toSet() )
    }

    @Entity
    @Table(name = "vault_fungible_states")
    class VaultFungibleStates(

            /** [ContractState] attributes */
//            @OneToMany
//            var participants: Set<Party>,

            /** [OwnableState] attributes */
            @OneToOne(cascade = arrayOf(CascadeType.PERSIST))
            @JoinColumn(name = "party_id", insertable = false, updatable = false)
            var owner: CommonSchemaV1.Party,

            /** [FungibleAsset] attributes
             *
             *  Note: the underlying Product being issued must be modelled into the
             *  custom contract itself (eg. see currency in Cash contract state)
             */

            @OneToMany(cascade = arrayOf(CascadeType.PERSIST))
            var exitKeys: Set<CommonSchemaV1.Party>,

            /** Amount attributes */

            @Column(name = "quantity")
            var quantity: Long,

            /** Issuer attributes */
            @OneToOne(cascade = arrayOf(CascadeType.PERSIST))
            @JoinColumn(name = "party_id")
            var issuerParty: CommonSchemaV1.Party,

            @Column(name = "issuer_reference")
            var issuerRef: ByteArray

    ) : PersistentState() {
        constructor(_owner: AbstractParty, _exitKeys: Collection<PublicKey>, _quantity: Long, _issuerParty: AbstractParty, _issuerRef: OpaqueBytes) :
                this(owner = CommonSchemaV1.Party(_owner),
                     exitKeys = _exitKeys.map { CommonSchemaV1.Party(AnonymousParty(it)) }.toSet(),
                     quantity = _quantity,
                     issuerParty = CommonSchemaV1.Party(_issuerParty),
                     issuerRef = _issuerRef.bytes)
    }
}