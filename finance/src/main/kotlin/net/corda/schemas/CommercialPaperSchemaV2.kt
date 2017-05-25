package net.corda.schemas

import net.corda.core.schemas.MappedSchema
import net.corda.node.services.vault.schemas.jpa.CommonSchemaV1
import java.time.Instant
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Index
import javax.persistence.Table

/**
 * Second version of a cash contract ORM schema that extends the common
 * [VaultFungibleState] abstract schema
 */
object CommercialPaperSchemaV2 : MappedSchema(schemaFamily = CommercialPaperSchema.javaClass, version = 1, mappedTypes = listOf(PersistentCommercialPaperState::class.java)) {
    @Entity
    @Table(name = "cp_states",
           indexes = arrayOf(Index(name = "ccy_code_index", columnList = "ccy_code"),
                             Index(name = "maturity_index", columnList = "maturity_instant"),
                             Index(name = "face_value_index", columnList = "face_value")))
    class PersistentCommercialPaperState(

            @Column(name = "maturity_instant")
            var maturity: Instant,

            @Column(name = "face_value")
            var faceValue: Long,

            @Column(name = "ccy_code", length = 3)
            var currency: String,

            @Column(name = "face_value_issuer_key")
            var faceValueIssuerParty: String,

            @Column(name = "face_value_issuer_ref")
            var faceValueIssuerRef: ByteArray

    ) : CommonSchemaV1.FungibleState()
}
