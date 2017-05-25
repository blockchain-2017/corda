package net.corda.node.services.contract.schemas.requery

import io.requery.*
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.requery.Requery
import java.time.Instant

object CommercialPaperSchema

/**
 * Commercial paper contract schema entity definition using Kotlin interface classes
 */
object CommercialPaperSchemaV3 : MappedSchema(schemaFamily = CommercialPaperSchema.javaClass, version = 3, mappedTypes = listOf(PersistentCommercialPaperState3::class.java)) {
    @Entity(model = "vault")
    @Table(name = "cp_states")
    interface PersistentCommercialPaperState3 : Requery.PersistentState {
        @get:Column(name = "issuance_key")
        var issuanceParty: String

        @get:Column(name = "issuance_ref")
        var issuanceRef: ByteArray

        @get:Column(name = "owner_key")
        @get:Index("ccy_code_index")
        var owner: String

        @get:Column(name = "maturity_instant")
        @get:Index("maturity_index")
        var maturity: Instant

        @get:Column(name = "face_value")
        @get:Index("face_value_index")
        var faceValue: Long

        @get:Column(name = "ccy_code", length = 3)
        var currency: String

        @get:Column(name = "face_value_issuer_key")
        var faceValueIssuerParty: String

        @get:Column(name = "face_value_issuer_ref")
        var faceValueIssuerRef: ByteArray
    }
}
