package net.corda.node.services.vault.schemas.jpa

import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.StatePersistable
import java.util.*
import javax.persistence.*

/**
 * JPA representation of the core Vault Schema
 */
object CommonSchema

/**
 * First version of the Vault ORM schema
 */
object CommonSchemaV1 : MappedSchema(schemaFamily = CommonSchema.javaClass, version = 1, mappedTypes = listOf(LinearState::class.java, Party::class.java)) {

    @Entity
    @Table(name = "vault_linear_states",
            indexes = arrayOf(Index(name = "external_id_index", columnList = "external_id"),
                              Index(name = "uuid_index", columnList = "uuid")))
    open class LinearState(

            /**
             *  Represents a [LinearState] [UniqueIdentifier]
             */
            @Column(name = "external_id")
            var externalId: String,

            @Column(name = "uuid", unique = true, nullable = false)
            var uuid: UUID

    ) : PersistentState() {
        constructor() : this("",UUID.randomUUID())
    }

    @MappedSuperclass
    open class FungibleState(

            /** [ContractState] attributes */
            @OneToMany
            var participants: Set<Party>,

            /** [OwnableState] attributes */
            @OneToOne(mappedBy = "id")
            var owner: Party,

            /** [FungibleAsset] attributes
             *
             *  Note: the underlying Product being issued must be modelled into the
             *  custom contract itself (eg. see currency in Cash contract state)
             */

            /** Amount attributes */

            @Column(name = "quantity")
            var quantity: Long,

            /** Issuer attributes */

            @OneToOne(mappedBy = "id")
            @Column(name = "issuer_party")
            var issuerParty: Party,

            @Column(name = "issuer_reference")
            var issuerRef: ByteArray

    ) : PersistentState() {
        constructor() : this(emptySet(), Party(), 0, Party(), kotlin.ByteArray(0))
    }

    class CustomState : StatePersistable

    @Entity
    @Table(name = "vault_party",
           indexes = arrayOf(Index(name = "party_name_idx", columnList = "party_name")))
    class Party(

            @Id
            @GeneratedValue
            @Column(name = "id")
            var id: Int,

            /**
             * [Party] attributes
             */
            @Column(name = "party_name")
            var name: String,

            @Column(name = "party_key")
            var key: String

    ) : PersistentState() {
        constructor() : this(0, "", "")
    }
}