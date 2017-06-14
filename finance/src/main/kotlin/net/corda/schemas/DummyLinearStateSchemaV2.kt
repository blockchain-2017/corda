package net.corda.schemas

import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.schemas.MappedSchema
import net.corda.node.services.vault.schemas.jpa.CommonSchemaV1
import java.time.Instant
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table
import javax.persistence.Transient

/**
 * Second version of a cash contract ORM schema that extends the common
 * [VaultLinearState] abstract schema
 */
object DummyLinearStateSchemaV2 : MappedSchema(schemaFamily = DummyLinearStateSchema.javaClass, version = 2,
                                               mappedTypes = listOf(PersistentDummyLinearState::class.java)) {
    @Entity
    @Table(name = "dummy_linear_states_v2")
    class PersistentDummyLinearState(
            @Column(name = "linear_string") var linearString: String,

            @Column(name = "linear_number") var linearNumber: Long,

            @Column(name = "linear_timestamp") var linearTimestamp: Instant,

            @Column(name = "linear_boolean") var linearBoolean: Boolean,

            /** parent attributes */
            @Transient
            val uid: UniqueIdentifier
    ) : CommonSchemaV1.LinearState(uid = uid)
}
