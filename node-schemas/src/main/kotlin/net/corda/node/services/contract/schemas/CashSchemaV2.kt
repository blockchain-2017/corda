package net.corda.node.services.contract.schemas

import io.requery.Column
import io.requery.Entity
import io.requery.Index
import io.requery.Table
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.requery.Requery

/**
 * An object used to fully qualify the [CashSchema] family name (i.e. independent of version).
 */
object CashSchema

/**
 * First version of a cash contract ORM schema that maps all fields of the [Cash] contract state as it stood
 * at the time of writing.
 */
object CashSchemaV2 : MappedSchema(schemaFamily = CashSchema.javaClass, version = 2, mappedTypes = listOf(PersistentCashState2::class.java)) {
        @Entity
        @Table(name = "cash_states")
        interface PersistentCashState2 : Requery.PersistentState {
                @get:Column(name = "owner_key")
                var owner: String

                @get:Index("monetary_value_idx")
                @get:Column(name = "monetary_value")
                var monetaryValue: Long

                @get:Index("ccy_code_idx")
                @get:Column(name = "ccy_code", length = 3)
                var currency: String

                @get:Column(name = "issuer_key")
                var issuerParty: String

                @get:Column(name = "issuer_ref")
                var issuerRef: ByteArray
        }
}