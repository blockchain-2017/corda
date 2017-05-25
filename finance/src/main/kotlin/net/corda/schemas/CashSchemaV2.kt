package net.corda.schemas

import net.corda.core.schemas.MappedSchema
import net.corda.node.services.vault.schemas.jpa.CommonSchemaV1
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Index
import javax.persistence.Table

/**
 * Second version of a cash contract ORM schema that extends the common
 * [VaultFungibleState] abstract schema
 */
object CashSchemaV2 : MappedSchema(schemaFamily = CashSchema.javaClass, version = 2, mappedTypes = listOf(PersistentCashState::class.java)) {
    @Entity
    @Table(name = "cash_states",
           indexes = arrayOf(Index(name = "ccy_code_idx", columnList = "ccy_code"),
                             Index(name = "pennies_idx", columnList = "pennies")))
    class PersistentCashState (

        /** product type */
        @Column(name = "ccy_code", length = 3)
        var currency: String

    ) : CommonSchemaV1.FungibleState()
}
