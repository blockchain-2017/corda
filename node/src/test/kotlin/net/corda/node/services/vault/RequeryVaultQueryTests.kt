package net.corda.node.services.vault

import net.corda.contracts.CommercialPaper
import net.corda.contracts.asset.DUMMY_CASH_ISSUER
import net.corda.core.contracts.DOLLARS
import net.corda.core.contracts.USD
import net.corda.core.contracts.`issued by`
import net.corda.core.days
import net.corda.core.node.services.VaultQueryService
import net.corda.core.node.services.VaultService
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.*
import net.corda.core.seconds
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.DUMMY_NOTARY
import net.corda.core.utilities.DUMMY_NOTARY_KEY
import net.corda.core.utilities.TEST_TX_TIME
import net.corda.node.services.contract.schemas.requery.CommercialPaperSchemaV3
import net.corda.node.utilities.configureDatabase
import net.corda.node.utilities.transaction
import net.corda.testing.MEGA_CORP
import net.corda.testing.MEGA_CORP_KEY
import net.corda.testing.node.MockServices
import net.corda.testing.node.makeTestDataSourceProperties
import org.assertj.core.api.Assertions
import org.junit.After
import org.junit.Before
import org.junit.Test

class RequeryVaultQueryTests : VaultQueryTests() {

//    @Before
//    fun setUp() {
//        val dataSourceProps = makeTestDataSourceProperties()
//        val dataSourceAndDatabase = configureDatabase(dataSourceProps)
//        dataSource = dataSourceAndDatabase.first
//        database = dataSourceAndDatabase.second
//        database.transaction {
//            services = object : MockServices(MEGA_CORP_KEY) {
//                override val vaultService: VaultService = makeVaultService(dataSourceProps)
//
//                override fun recordTransactions(txs: Iterable<SignedTransaction>) {
//                    for (stx in txs) {
//                        storageService.validatedTransactions.addTransaction(stx)
//                    }
//                    // Refactored to use notifyAll() as we have no other unit test for that method with multiple transactions.
//                    vaultService.notifyAll(txs.map { it.tx })
//                }
//                override val vaultQueryService : VaultQueryService = RequeryVaultQueryServiceImpl(dataSourceProps)
//            }
//        }
//    }
//
//    @After
//    fun tearDown() {
//        dataSource.close()
//    }

    // specifying Query on Commercial Paper contract state attributes
    @Test
    fun `custom query using Requery annotations - commercial paper`() {
        database.transaction {

            // MegaCorp™ issues $10,000 of commercial paper, to mature in 30 days, owned by itself.
            val faceValue = 10000.DOLLARS `issued by` DUMMY_CASH_ISSUER
            val issuance = MEGA_CORP.ref(1)
            val commercialPaper =
                    CommercialPaper().generateIssue(issuance, faceValue, TEST_TX_TIME + 30.days, DUMMY_NOTARY).apply {
                        setTime(TEST_TX_TIME, 30.seconds)
                        signWith(MEGA_CORP_KEY)
                        signWith(DUMMY_NOTARY_KEY)
                    }.toSignedTransaction()
            services.recordTransactions(commercialPaper)

            val ccyIndex = LogicalExpression(CommercialPaperSchemaV3.PersistentCommercialPaperState3::currency, Operator.EQUAL, USD.currencyCode)
            val maturityIndex = LogicalExpression(CommercialPaperSchemaV3.PersistentCommercialPaperState3::maturity, Operator.GREATER_THAN_OR_EQUAL, TEST_TX_TIME + 30.days)
            val faceValueIndex = LogicalExpression(CommercialPaperSchemaV3.PersistentCommercialPaperState3::faceValue, Operator.GREATER_THAN_OR_EQUAL, 10000L)

            val criteria1 = QueryCriteria.VaultCustomQueryCriteria(ccyIndex)
            val criteria2 = QueryCriteria.VaultCustomQueryCriteria(maturityIndex)
            val criteria3 = QueryCriteria.VaultCustomQueryCriteria(faceValueIndex)

            val result = vaultQuerySvc.queryBy<CommercialPaper.State>(criteria1.and(criteria3).or(criteria2))

            Assertions.assertThat(result.states).hasSize(1)
            Assertions.assertThat(result.statesMetadata).hasSize(1)
        }
    }
}


