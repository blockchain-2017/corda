package net.corda.node.services.contract.schemas

import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.StatePersistable
import java.time.Instant
import javax.persistence.*

/**
 * An object used to fully qualify the [CommercialPaperSchema] family name (i.e. independent of version).
 */
object CommercialPaperSchema

/**
 * Commercial paper contract schema entity definition using JPA annotations
 */
//object CommercialPaperSchemaV2 : MappedSchema(schemaFamily = CommercialPaperSchema.javaClass, version = 2, mappedTypes = listOf(PersistentCommercialPaperState2::class.java)) {
//    @Entity()
//    @Table(name = "cp_states",
//           indexes = arrayOf(Index(name = "ccy_code_index", columnList = "ccy_code"),
//                             Index(name = "maturity_index", columnList = "maturity_instant"),
//                             Index(name = "face_value_index", columnList = "face_value")))
//    class PersistentCommercialPaperState2(
//
//            /** PK - should be in superclass **/
//            @Id
//            @Column(name = "transaction_id", length = 64)
//            var txId: String
//
//            @Id
//            @Column(name = "output_index")
//            var index: Int,
//
//            /** state atrtibutes */
//            @Column(name = "issuance_key")
//            var issuanceParty: String,
//
//            @Column(name = "issuance_ref")
//            var issuanceRef: ByteArray,
//
//            @Column(name = "owner_key")
//            var owner: String,
//
//            @Column(name = "maturity_instant")
//            var maturity: Instant,
//
//            @Column(name = "face_value")
//            var faceValue: Long,
//
//            @Column(name = "ccy_code", length = 3)
//            var currency: String,
//
//            @Column(name = "face_value_issuer_key")
//            var faceValueIssuerParty: String,
//
//            @Column(name = "face_value_issuer_ref")
//            var faceValueIssuerRef: ByteArray
//
//    ) : StatePersistable
//}
