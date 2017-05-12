package net.corda.node.services.contract.schemas

import io.requery.*
import net.corda.core.schemas.MappedSchema
import java.time.Instant

/**
 * Commercial paper contract schema entity definition using Kotlin immutable data classes
 */
object CommercialPaperSchemaV4 : MappedSchema(schemaFamily = CommercialPaperSchema.javaClass, version = 4, mappedTypes = listOf(PersistentCommercialPaperState4::class.java)) {
    @Entity(model = "contract")
    @Table(name = "cp_states")
    data class PersistentCommercialPaperState4 constructor (
        @get:Column(name = "issuance_key")
        var issuanceParty: String,

        @get:Column(name = "issuance_ref")
        var issuanceRef: ByteArray,

        @get:Column(name = "owner_key")
        @get:Index("ccy_code_index")
        var owner: String,

        @get:Column(name = "maturity_instant")
        @get:Index("maturity_index")
        var maturity: Instant,

        @get:Column(name = "face_value")
        @get:Index("face_value_index")
        var faceValue: Long,

        @get:Column(name = "ccy_code", length = 3)
        var currency: String,

        @get:Column(name = "face_value_issuer_key")
        var faceValueIssuerParty: String,

        @get:Column(name = "face_value_issuer_ref")
        var faceValueIssuerRef: ByteArray
    ) : Persistable
}
