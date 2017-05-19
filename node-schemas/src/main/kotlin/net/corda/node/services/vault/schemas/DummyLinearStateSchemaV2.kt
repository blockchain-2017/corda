package net.corda.node.services.vault.schemas

import io.requery.Column
import io.requery.Entity
import io.requery.Index
import io.requery.Table
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.requery.Requery
import java.time.Instant
import java.util.*


/**
 * An object used to fully qualify the [DummyLinearStateSchema] family name (i.e. independent of version).
 */
object DummyLinearStateSchema

/**
 * First version of a cash contract ORM schema that maps all fields of the [DummyLinearState] contract state as it stood
 * at the time of writing.
 */
object DummyLinearStateSchemaV2 : MappedSchema(schemaFamily = DummyLinearStateSchema.javaClass, version = 2, mappedTypes = listOf(PersistentDummyLinearState2::class.java)) {
    @Entity()
    @Table(name = "contract_dummy_linear_states")

    interface PersistentDummyLinearState2 : Requery.PersistentState {

        /**
         * UniqueIdentifier
         */

        @get:Index("external_id_index")
        @get:Column(name = "external_id")
        var externalId: String?

        @get:Index("uuid_index")
        @get:Column(name = "uuid", unique = true, nullable = false)
        var uuid: UUID

        /**
         *  Dummy attributes
         */

        @get:Column(name = "linear_string")
        var linearString: String

        @get:Column(name = "linear_number")
        var linearNumber: Long

        @get:Column(name = "linear_timestamp")
        var linearTimestamp: Instant

        @get:Column(name = "linear_boolean")
        var linearBoolean: Boolean
    }
}
