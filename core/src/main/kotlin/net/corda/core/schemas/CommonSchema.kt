package net.corda.node.services.vault.schemas.jpa

import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.toBase58String
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.StatePersistable
import java.util.*
import javax.persistence.*

/**
 * JPA representation of the common schema entities
 */
object CommonSchema

/**
 * First version of the Vault ORM schema
 */
object CommonSchemaV1 : MappedSchema(schemaFamily = CommonSchema.javaClass, version = 1, mappedTypes = listOf(Party::class.java)) {

    @MappedSuperclass
    open class LinearState(
            /**
             *  Represents a [LinearState] [UniqueIdentifier]
             */
            @Column(name = "external_id")
            var externalId: String?,

            @Column(name = "uuid", nullable = false)
            var uuid: UUID

    ) : PersistentState() {
        constructor(uid: UniqueIdentifier) : this(externalId = uid.externalId, uuid = uid.id)
    }

    @MappedSuperclass
    open class FungibleState(

            /** [ContractState] attributes */
//            @OneToMany
//            var participants: Set<Party>,

            /** [OwnableState] attributes */
            @Column(name = "owner_key")
            var ownerKey: String,

            /** [FungibleAsset] attributes
             *
             *  Note: the underlying Product being issued must be modelled into the
             *  custom contract itself (eg. see currency in Cash contract state)
             */

//            @OneToMany
//            var exitKeys: Set<Party>,

            /** Amount attributes */

            @Column(name = "quantity")
            var quantity: Long,

            /** Issuer attributes */
//            @OneToOne
//            @JoinColumn(name = "party_id")
//            var issuerParty: Party,

            @Column(name = "issuer_party_name")
            var issuerPartyName: String,

            @Column(name = "issuer_reference")
            var issuerRef: ByteArray

    ) : PersistentState() {
        constructor() : this("", 0L, "", ByteArray(0))
        constructor(_ownerKey: String, _quantity: Long, _issuerParty: net.corda.core.identity.AbstractParty, _issuerRef: ByteArray)
                : this(ownerKey = _ownerKey, quantity = _quantity, issuerPartyName = _issuerParty.nameOrNull()?.toString() ?: _issuerParty.toString(), issuerRef = _issuerRef)
    }

    class CustomState : StatePersistable

    /**
     *  Party entity (to be replaced by referencing final Identity Schema)
     */
    @Entity
    @Table(name = "vault_party",
            indexes = arrayOf(Index(name = "party_name_idx", columnList = "party_name")))
    class Party(
            @Id
            @GeneratedValue
            @Column(name = "party_id")
            var id: Int,

            /**
             * [Party] attributes
             */
            @Column(name = "party_name")
            var name: String,

            @Column(name = "party_key")
            var key: String
    ) {
        constructor(party: net.corda.core.identity.AbstractParty) : this(0, party.nameOrNull()?.toString() ?: party.toString(), party.owningKey.toBase58String())
    }
}