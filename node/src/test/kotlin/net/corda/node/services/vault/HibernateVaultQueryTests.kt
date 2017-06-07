package net.corda.node.services.vault

import net.corda.contracts.CommercialPaper
import net.corda.contracts.asset.DUMMY_CASH_ISSUER
import net.corda.core.contracts.DOLLARS
import net.corda.core.contracts.POUNDS
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
import net.corda.node.services.database.HibernateConfiguration
import net.corda.node.services.schema.NodeSchemaService
import net.corda.node.utilities.configureDatabase
import net.corda.node.utilities.transaction
import net.corda.schemas.CommercialPaperSchemaV1
import net.corda.schemas.CommercialPaperSchemaV2
import net.corda.testing.MEGA_CORP
import net.corda.testing.MEGA_CORP_KEY
import net.corda.testing.node.MockServices
import net.corda.testing.node.makeTestDataSourceProperties
import org.assertj.core.api.Assertions
import org.junit.After
import org.junit.Before
import org.junit.Test

class HibernateVaultQueryTests : VaultQueryTests() {

//    @Before
//    fun setUp() {
//        val dataSourceProps = makeTestDataSourceProperties()
//        val dataSourceAndDatabase = configureDatabase(dataSourceProps)
//        dataSource = dataSourceAndDatabase.first
//        database = dataSourceAndDatabase.second
//        database.transaction {
//            val hibernateConfig = HibernateConfiguration(NodeSchemaService())
//            services = object : MockServices(MEGA_CORP_KEY) {
//                override val vaultService: VaultService = makeVaultService(dataSourceProps, hibernateConfig)
//
//                override fun recordTransactions(txs: Iterable<SignedTransaction>) {
//                    for (stx in txs) {
//                        storageService.validatedTransactions.addTransaction(stx)
//                    }
//                    // Refactored to use notifyAll() as we have no other unit test for that method with multiple transactions.
//                    vaultService.notifyAll(txs.map { it.tx })
//                }
//                override val vaultQueryService : VaultQueryService = HibernateVaultQueryImpl(hibernateConfig)
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
    fun `custom query using JPA - commercial paper schema V1 single attribute`() {
        database.transaction {

            val issuance = MEGA_CORP.ref(1)

            // MegaCorp™ issues $10,000 of commercial paper, to mature in 30 days, owned by itself.
            val faceValue = 10000.DOLLARS `issued by` DUMMY_CASH_ISSUER
            val commercialPaper =
                    CommercialPaper().generateIssue(issuance, faceValue, TEST_TX_TIME + 30.days, DUMMY_NOTARY).apply {
                        setTime(TEST_TX_TIME, 30.seconds)
                        signWith(MEGA_CORP_KEY)
                        signWith(DUMMY_NOTARY_KEY)
                    }.toSignedTransaction()
            services.recordTransactions(commercialPaper)

            // MegaCorp™ now issues £10,000 of commercial paper, to mature in 30 days, owned by itself.
            val faceValue2 = 10000.POUNDS `issued by` DUMMY_CASH_ISSUER
            val commercialPaper2 =
                    CommercialPaper().generateIssue(issuance, faceValue2, TEST_TX_TIME + 30.days, DUMMY_NOTARY).apply {
                        setTime(TEST_TX_TIME, 30.seconds)
                        signWith(MEGA_CORP_KEY)
                        signWith(DUMMY_NOTARY_KEY)
                    }.toSignedTransaction()
            services.recordTransactions(commercialPaper2)

            val ccyIndex = LogicalExpression(CommercialPaperSchemaV1.PersistentCommercialPaperState::currency, Operator.EQUAL, USD.currencyCode)
            val criteria1 = QueryCriteria.VaultCustomQueryCriteria(ccyIndex)

            val result = vaultQuerySvc.queryBy<CommercialPaper.State>(criteria1)

            Assertions.assertThat(result.states).hasSize(1)
            Assertions.assertThat(result.statesMetadata).hasSize(1)
        }
    }

    // specifying Query on Commercial Paper contract state attributes
    @Test
    fun `custom query using JPA - commercial paper schema V1 - multiple attributes`() {
        database.transaction {

            val issuance = MEGA_CORP.ref(1)

            // MegaCorp™ issues $10,000 of commercial paper, to mature in 30 days, owned by itself.
            val faceValue = 10000.DOLLARS `issued by` DUMMY_CASH_ISSUER
            val commercialPaper =
                    CommercialPaper().generateIssue(issuance, faceValue, TEST_TX_TIME + 30.days, DUMMY_NOTARY).apply {
                        setTime(TEST_TX_TIME, 30.seconds)
                        signWith(MEGA_CORP_KEY)
                        signWith(DUMMY_NOTARY_KEY)
                    }.toSignedTransaction()
            services.recordTransactions(commercialPaper)

            // MegaCorp™ now issues £5,000 of commercial paper, to mature in 30 days, owned by itself.
            val faceValue2 = 5000.POUNDS `issued by` DUMMY_CASH_ISSUER
            val commercialPaper2 =
                    CommercialPaper().generateIssue(issuance, faceValue2, TEST_TX_TIME + 30.days, DUMMY_NOTARY).apply {
                        setTime(TEST_TX_TIME, 30.seconds)
                        signWith(MEGA_CORP_KEY)
                        signWith(DUMMY_NOTARY_KEY)
                    }.toSignedTransaction()
            services.recordTransactions(commercialPaper2)

            val ccyIndex = LogicalExpression(CommercialPaperSchemaV1.PersistentCommercialPaperState::currency, Operator.EQUAL, USD.currencyCode)
            val maturityIndex = LogicalExpression(CommercialPaperSchemaV1.PersistentCommercialPaperState::maturity, Operator.GREATER_THAN_OR_EQUAL, TEST_TX_TIME + 30.days)
            val faceValueIndex = LogicalExpression(CommercialPaperSchemaV1.PersistentCommercialPaperState::faceValue, Operator.GREATER_THAN_OR_EQUAL, 10000L)

            val criteria1 = QueryCriteria.VaultCustomQueryCriteria(ccyIndex)
            val criteria2 = QueryCriteria.VaultCustomQueryCriteria(maturityIndex)
            val criteria3 = QueryCriteria.VaultCustomQueryCriteria(faceValueIndex)

            val result = vaultQuerySvc.queryBy<CommercialPaper.State>(criteria1.and(criteria3).and(criteria2))

            Assertions.assertThat(result.states).hasSize(1)
            Assertions.assertThat(result.statesMetadata).hasSize(1)
        }
    }

    // specifying Query on Commercial Paper contract state attributes
    @Test
    fun `custom query using JPA - commercial paper schema V2`() {
        database.transaction {

            val issuance = MEGA_CORP.ref(1)

            // MegaCorp™ issues $10,000 of commercial paper, to mature in 30 days, owned by itself.
            val faceValue = 10000.DOLLARS `issued by` DUMMY_CASH_ISSUER
            val commercialPaper =
                    CommercialPaper().generateIssue(issuance, faceValue, TEST_TX_TIME + 30.days, DUMMY_NOTARY).apply {
                        setTime(TEST_TX_TIME, 30.seconds)
                        signWith(MEGA_CORP_KEY)
                        signWith(DUMMY_NOTARY_KEY)
                    }.toSignedTransaction()
            services.recordTransactions(commercialPaper)

            // MegaCorp™ now issues £10,000 of commercial paper, to mature in 30 days, owned by itself.
            val faceValue2 = 10000.POUNDS `issued by` DUMMY_CASH_ISSUER
            val commercialPaper2 =
                    CommercialPaper().generateIssue(issuance, faceValue2, TEST_TX_TIME + 30.days, DUMMY_NOTARY).apply {
                        setTime(TEST_TX_TIME, 30.seconds)
                        signWith(MEGA_CORP_KEY)
                        signWith(DUMMY_NOTARY_KEY)
                    }.toSignedTransaction()
            services.recordTransactions(commercialPaper2)

            val ccyIndex = LogicalExpression(CommercialPaperSchemaV2.PersistentCommercialPaperState::currency, Operator.EQUAL, USD.currencyCode)
            val maturityIndex = LogicalExpression(CommercialPaperSchemaV2.PersistentCommercialPaperState::maturity, Operator.GREATER_THAN_OR_EQUAL, TEST_TX_TIME + 30.days)
            val faceValueIndex = LogicalExpression(CommercialPaperSchemaV2.PersistentCommercialPaperState::quantity, Operator.GREATER_THAN_OR_EQUAL, 10000L)

            val criteria1 = QueryCriteria.VaultCustomQueryCriteria(ccyIndex)
            val criteria2 = QueryCriteria.VaultCustomQueryCriteria(maturityIndex)
            val criteria3 = QueryCriteria.VaultCustomQueryCriteria(faceValueIndex)

            val result = vaultQuerySvc.queryBy<CommercialPaper.State>(criteria1.and(criteria3).and(criteria2))

            Assertions.assertThat(result.states).hasSize(1)
            Assertions.assertThat(result.statesMetadata).hasSize(1)
        }
    }
}


