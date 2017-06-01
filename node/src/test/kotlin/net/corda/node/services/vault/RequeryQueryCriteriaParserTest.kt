package net.corda.node.services.vault

import io.requery.Persistable
import io.requery.sql.KotlinEntityDataStore
import net.corda.contracts.asset.Cash
import net.corda.contracts.asset.DUMMY_CASH_ISSUER
import net.corda.contracts.testing.fillWithSomeTestCash
import net.corda.contracts.testing.fillWithSomeTestDeals
import net.corda.contracts.testing.fillWithSomeTestLinearStates
import net.corda.core.contracts.*
import net.corda.core.days
import net.corda.core.node.services.Vault
import net.corda.core.node.services.VaultService
import net.corda.core.node.services.vault.*
import net.corda.core.serialization.OpaqueBytes
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.DUMMY_NOTARY
import net.corda.core.utilities.TEST_TX_TIME
import net.corda.node.services.Models
import net.corda.node.services.contract.schemas.requery.CashSchemaV2
import net.corda.node.services.contract.schemas.requery.CommercialPaperSchemaV3
import net.corda.node.services.contract.schemas.requery.CommercialPaperSchemaV4
import net.corda.node.services.contract.schemas.requery.DummyLinearStateSchemaV2
import net.corda.node.services.database.RequeryConfiguration
import net.corda.node.services.vault.schemas.requery.VaultSchema
import net.corda.node.utilities.configureDatabase
import net.corda.node.utilities.transaction
import net.corda.schemas.CommercialPaperSchemaV1
import net.corda.testing.*
import net.corda.testing.node.MockServices
import net.corda.testing.node.makeTestDataSourceProperties
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.sql.Database
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.time.temporal.ChronoUnit
import java.util.*

class RequeryQueryCriteriaParserTest {

    lateinit var services: MockServices
    lateinit var dataSource: Closeable
    lateinit var database: Database
    lateinit var requerySession: KotlinEntityDataStore<Persistable>

    // Contract states
    lateinit var linearStates: Iterable<StateAndRef<LinearState>>
    lateinit var dealStates: Iterable<StateAndRef<DealState>>
    lateinit var cashStates: List<StateRef>

    // Linear Ids
    private val linearId1 = "ID1"
    private val linearId2 = "ID2"

    // Deal Refs
    private val DEAL_REF1 = "123"
    private val DEAL_REF2 = "456"
    private val DEAL_REF3 = "789"

    @Before
    fun setUp() {
        val dataSourceProps = makeTestDataSourceProperties()
        val dataSourceAndDatabase = configureDatabase(dataSourceProps)
        dataSource = dataSourceAndDatabase.first
        database = dataSourceAndDatabase.second
        database.transaction {
            services = object : MockServices() {
                override val vaultService: VaultService = makeVaultService(dataSourceProps)

                override fun recordTransactions(txs: Iterable<SignedTransaction>) {
                    for (stx in txs) {
                        storageService.validatedTransactions.addTransaction(stx)
                    }
                    // Refactored to use notifyAll() as we have no other unit test for that method with multiple transactions.
                    vaultService.notifyAll(txs.map { it.tx })
                }
            }
        }

        database.transaction {
            cashStates = services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 3, 3, Random(0L), ownedBy = MINI_CORP_PUBKEY).states.map { it.ref }
                    .plus(services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 1, 1, Random(0L), issuedBy = (DUMMY_CASH_ISSUER)).states.map { it.ref })
                    .plus(services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 1, 1, Random(0L), issuedBy = (BOC.ref(1)), issuerKey = BOC_KEY, ref = OpaqueBytes.of(1)).states.map { it.ref })
            linearStates = services.fillWithSomeTestLinearStates(1, linearId1, participants = listOf(MEGA_CORP_PUBKEY, MINI_CORP_PUBKEY)).states.toList()
                    .plus(services.fillWithSomeTestLinearStates(2, linearId2, participants = listOf(MEGA_CORP_PUBKEY, MINI_CORP_PUBKEY)).states.toList())
            dealStates = services.fillWithSomeTestDeals(listOf(DEAL_REF1), parties = listOf(MEGA_CORP.toAnonymous(), BIG_CORP.toAnonymous())).states.toList()
                   .plus(services.fillWithSomeTestDeals(listOf(DEAL_REF2, DEAL_REF3), parties = listOf(BIG_CORP.toAnonymous(), MINI_CORP.toAnonymous())).states)
        }

        val requeryConfig = RequeryConfiguration(dataSourceProps, true)
        requerySession = requeryConfig.sessionForModel(Models.VAULT)
    }

    @After
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun parse() {
    }

    @Test
    fun `contract state types`() {
        val contractStateTypes = deriveContractTypes<Cash.State>()
        assertThat(contractStateTypes).hasSize(1)
        assertThat(contractStateTypes.first()).isEqualTo(Cash.State::class.java)
    }

    @Test
    fun `derive contract state entities`() {

        requerySession.invoke {

            val query = select(VaultSchema.VaultStates::class)
            val criteriaParse = RequeryQueryCriteriaParser(mapOf(FungibleAsset::class.java.name to listOf(Cash.State::class.java.name)), query)

            // Fungible criteria (Cash)
            val criteriaFungible = QueryCriteria.FungibleAssetQueryCriteria()
            val contractStateEntitiesFungible = criteriaParse.deriveEntities(criteriaFungible)
            assertThat(contractStateEntitiesFungible).hasSize(2)
            assertThat(contractStateEntitiesFungible[0]).isEqualTo(VaultSchema.VaultStates::class.java)
            assertThat(contractStateEntitiesFungible[1]).isEqualTo(CashSchemaV2.PersistentCashState2::class.java)

            // Linear State (DummyLinearState)
            val criteria = QueryCriteria.LinearStateQueryCriteria()
            val contractStateEntities = criteriaParse.deriveEntities(criteria)
            assertThat(contractStateEntities).hasSize(2)
            assertThat(contractStateEntities[0]).isEqualTo(VaultSchema.VaultStates::class.java)
            assertThat(contractStateEntities[1]).isEqualTo(DummyLinearStateSchemaV2.PersistentDummyLinearState2::class.java)

            // Commercial Paper
            val expressionCustom = LogicalExpression(CommercialPaperSchemaV3.PersistentCommercialPaperState3::currency, Operator.EQUAL, USD.currencyCode)

            val criteriaCustom = QueryCriteria.VaultCustomQueryCriteria(expressionCustom)
            val contractStateEntitiesCustom = criteriaParse.deriveEntities(criteriaCustom)
            assertThat(contractStateEntitiesCustom).hasSize(2)
            assertThat(contractStateEntitiesCustom[0]).isEqualTo(VaultSchema.VaultStates::class.java)
            assertThat(contractStateEntitiesCustom[1]).isEqualTo(CommercialPaperSchemaV3.PersistentCommercialPaperState3::class.java)
        }
    }

    @Test
    fun parseVaultQueryCriteria() {

        requerySession.invoke {

            val query = select(VaultSchema.VaultStates::class)
            val criteriaParse = RequeryQueryCriteriaParser(mapOf(FungibleAsset::class.java.name to listOf(Cash.State::class.java.name)), query)

            val status = Vault.StateStatus.UNCONSUMED
            val criteria1 = QueryCriteria.VaultQueryCriteria(status = status)
            criteriaParse.parse(criteria1)

            val stateRefs = cashStates
            val criteria2 = QueryCriteria.VaultQueryCriteria(stateRefs = stateRefs)
            criteriaParse.parse(criteria2)

            val contractStateTypes = setOf(Cash.State::class.java, DealState::class.java)
            val criteria3 = QueryCriteria.VaultQueryCriteria(contractStateTypes = contractStateTypes)
            criteriaParse.parse(criteria3)

            val notaryName = listOf(DUMMY_NOTARY.name)
            val criteria4 = QueryCriteria.VaultQueryCriteria(notaryName = notaryName)
            criteriaParse.parse(criteria4)

            val criteria5 = QueryCriteria.VaultQueryCriteria(includeSoftlockedStates = false)
            criteriaParse.parse(criteria5)

            val participants = listOf(MEGA_CORP.name, MINI_CORP.name)
            val criteria6 = QueryCriteria.VaultQueryCriteria(participantIdentities = participants)
            criteriaParse.parse(criteria6)

            val start = TEST_TX_TIME
            val end = TEST_TX_TIME.plus(30, ChronoUnit.DAYS)
            val recordedBetweenExpression = LogicalExpression(QueryCriteria.TimeInstantType.RECORDED, Operator.BETWEEN, arrayOf(start, end))
            val criteria7 = QueryCriteria.VaultQueryCriteria(timeCondition = recordedBetweenExpression)
            criteriaParse.parse(criteria7)

            val criteriaAnd = criteria1.and(criteria2)
            criteriaParse.parse(criteriaAnd)

            val criteriaOr = criteria4.or(criteria6)
            criteriaParse.parse(criteriaOr)

            fail()
        }
    }

    @Test
    fun parseLinearStateQueryCriteria() {

        requerySession.invoke {

            val query = select(VaultSchema.VaultStates::class)
            val criteriaParse = RequeryQueryCriteriaParser(mapOf(FungibleAsset::class.java.name to listOf(Cash.State::class.java.name)), query)

            val linearIds = linearStates.map { it.state.data.linearId }
            val criteria1 = QueryCriteria.LinearStateQueryCriteria(linearId = linearIds)
            criteriaParse.parse(criteria1)

            val dealRefs = listOf(DEAL_REF1, DEAL_REF2)
            val criteria2 = QueryCriteria.LinearStateQueryCriteria(dealRef = dealRefs)
            criteriaParse.parse(criteria2)

            val dealParties = listOf(MEGA_CORP.name, MINI_CORP.name)
            val criteria3 = QueryCriteria.LinearStateQueryCriteria(dealPartyName = dealParties)
            criteriaParse.parse(criteria3)

            val criteriaAnd = criteria1.and(criteria2)
            criteriaParse.parse(criteriaAnd)

            val criteriaOr = criteria2.or(criteria3)
            criteriaParse.parse(criteriaOr)

            fail()
        }
    }

    @Test
    fun FungibleAssetQueryCriteria() {

        requerySession.invoke {

            val query = select(VaultSchema.VaultStates::class)
            val criteriaParse = RequeryQueryCriteriaParser(mapOf(FungibleAsset::class.java.name to listOf(Cash.State::class.java.name)), query)

            val owners = listOf(MINI_CORP.name)
            val criteria1 = QueryCriteria.FungibleAssetQueryCriteria(owner = owners)
            criteriaParse.parse(criteria1)

            val quantityExpression = LogicalExpression(this, Operator.GREATER_THAN, 50L)
            val criteria4 = QueryCriteria.FungibleAssetQueryCriteria(quantity = quantityExpression)
            criteriaParse.parse(criteria4)

            val issuerPartyNames = listOf(BOC.name)
            val criteria5 = QueryCriteria.FungibleAssetQueryCriteria(issuerPartyName = issuerPartyNames)
            criteriaParse.parse(criteria5)

            val issuerPartyRefs = listOf(BOC.ref(1).reference)
            val criteria6 = QueryCriteria.FungibleAssetQueryCriteria(issuerRef = issuerPartyRefs)
            criteriaParse.parse(criteria6)

            val exitKeyIds = listOf(getTestX509Name("TEST"))
            val criteria7 = QueryCriteria.FungibleAssetQueryCriteria(exitKeys = exitKeyIds)
            criteriaParse.parse(criteria7)

            val criteriaOr = criteria1.or(criteria4)
            criteriaParse.parse(criteriaOr)

            fail()
        }
    }

    /** Commercial Paper V1 (Hibernate JPA */
    @Test
    fun VaultCustomQueryCriteriaSingleExpression() {

        requerySession.invoke {

            val query = select(VaultSchema.VaultStates::class)
            val criteriaParse = RequeryQueryCriteriaParser(mapOf(FungibleAsset::class.java.name to listOf(Cash.State::class.java.name)), query)

            // Commercial Paper
            val expression = LogicalExpression(CommercialPaperSchemaV1.PersistentCommercialPaperState::currency, Operator.EQUAL, USD.currencyCode)
            val criteria1 = QueryCriteria.VaultCustomQueryCriteria(expression)
            criteriaParse.parse(criteria1)

            fail()
        }
    }

    /** Commercial Paper V2 (Requery JPA) */
//    @Test
//    fun VaultCustomQueryCriteriaSingleExpressionJPA() {
//
//        // Commercial Paper
//        val expression = LogicalExpression(CommercialPaperSchemaV2.PersistentCommercialPaperState2::currency, Operator.EQUAL, USD.currencyCode)
//        val criteria1 = QueryCriteria.VaultCustomQueryCriteria(expression)
//        criteriaParse.parse(criteria1)
//
//        fail()
//    }

    /** Commercial Paper V3 (Requery Kotlin interface class) */
    @Test
    fun VaultCustomQueryCriteriaSingleExpressionInterface() {

        requerySession.invoke {

            val query = select(VaultSchema.VaultStates::class)
            val criteriaParse = RequeryQueryCriteriaParser(mapOf(FungibleAsset::class.java.name to listOf(Cash.State::class.java.name)), query)

            // Commercial Paper
            val expression = LogicalExpression(CommercialPaperSchemaV3.PersistentCommercialPaperState3::currency, Operator.EQUAL, USD.currencyCode)
            val criteria1 = QueryCriteria.VaultCustomQueryCriteria(expression)

            criteriaParse.parseCriteria(criteria1)
        }
    }

    /** Commercial Paper V4 (Requery Kotlin data class) */
    @Test
    fun VaultCustomQueryCriteriaSingleExpressionDataClass() {

        requerySession.invoke {

            val query = select(VaultSchema.VaultStates::class)
            val criteriaParse = RequeryQueryCriteriaParser(mapOf(FungibleAsset::class.java.name to listOf(Cash.State::class.java.name)), query)

            // Commercial Paper
            val expression = LogicalExpression(CommercialPaperSchemaV4.PersistentCommercialPaperState4::currency, Operator.EQUAL, USD.currencyCode)
            val criteria1 = QueryCriteria.VaultCustomQueryCriteria(expression)
            criteriaParse.parse(criteria1)

            fail()
        }
    }

    @Test
    fun VaultCustomQueryCriteriaCombinedExpressions() {

        requerySession.invoke {

            val query = select(VaultSchema.VaultStates::class)
            val criteriaParse = RequeryQueryCriteriaParser(mapOf(FungibleAsset::class.java.name to listOf(Cash.State::class.java.name)), query)

            // Commercial Paper
            val ccyExpr = LogicalExpression(CommercialPaperSchemaV3.PersistentCommercialPaperState3::currency, Operator.EQUAL, USD.currencyCode)
            val maturityExpr = LogicalExpression(CommercialPaperSchemaV3.PersistentCommercialPaperState3::maturity, Operator.GREATER_THAN_OR_EQUAL, TEST_TX_TIME + 30.days)
            val faceValueExpr = LogicalExpression(CommercialPaperSchemaV3.PersistentCommercialPaperState3::faceValue, Operator.GREATER_THAN_OR_EQUAL, 10000L)

            val criteria1 = QueryCriteria.VaultCustomQueryCriteria(ccyExpr)
            val criteria2 = QueryCriteria.VaultCustomQueryCriteria(maturityExpr)
            val criteria3 = QueryCriteria.VaultCustomQueryCriteria(faceValueExpr)
            criteriaParse.parse(criteria1.and(criteria2).or(criteria3))

            fail()
        }
    }

    @Test
    fun `combining vault and linear state criteria`() {

        fail()
    }

    @Test
    fun `combining vault and fungible state criteria`() {

        fail()
    }

    @Test
    fun `combining linear and custom state criteria`() {

        fail()
    }

    @Test
    fun `combining fungible and custom state criteria`() {

        fail()
    }

    @Test
    fun `combining fungible and linear state criteria is invalid`() {

        fail()
    }

    @Test
    fun `combining vault and linear and custom state criteria`() {

        fail()
    }

    @Test
    fun `combining vault and fungible and custom state criteria`() {

        fail()
    }

    @Test
    fun parseSorting() {
    }

}