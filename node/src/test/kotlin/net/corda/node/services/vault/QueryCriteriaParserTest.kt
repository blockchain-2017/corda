package net.corda.node.services.vault

import io.requery.kotlin.findAttribute
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
import net.corda.node.services.contract.schemas.CommercialPaperSchemaV2
import net.corda.node.services.vault.schemas.CommercialPaperSchemaV3
import net.corda.node.services.contract.schemas.CommercialPaperSchemaV4
import net.corda.node.services.vault.schemas.VaultSchema
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
import kotlin.reflect.KMutableProperty1

class QueryCriteriaParserTest {

    lateinit var services: MockServices
    lateinit var dataSource: Closeable
    lateinit var database: Database

    // Contract states
    lateinit var linearStates: Iterable<StateAndRef<LinearState>>
    lateinit var dealStates: Iterable<StateAndRef<DealState>>
    lateinit var cashStates: List<StateRef>

    // Linear Ids
    private val linearId1 = UniqueIdentifier("ID1")
    private val linearId2 = UniqueIdentifier("ID2")

    // Deal Refs
    private val DEAL_REF1 = "123"
    private val DEAL_REF2 = "456"
    private val DEAL_REF3 = "789"

    lateinit var criteriaParse: QueryCriteriaParser

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
            dealStates = services.fillWithSomeTestDeals(listOf(DEAL_REF1), 3, parties = listOf(MEGA_CORP.toAnonymous(), BIG_CORP.toAnonymous())).states.toList()
                   .plus(services.fillWithSomeTestDeals(listOf(DEAL_REF2, DEAL_REF3), parties = listOf(BIG_CORP.toAnonymous(), MINI_CORP.toAnonymous())).states)

        }

        criteriaParse = QueryCriteriaParser()
    }

    @After
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun parse() {
    }

    @Test
    fun parseVaultQueryCriteria() {

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

    @Test
    fun parseLinearStateQueryCriteria() {

        val linearIds = listOf(linearId1)
        val criteria1 = QueryCriteria.LinearStateQueryCriteria(linearId = linearIds)
        val logicalCondition = criteriaParse.parse(criteria1)
        assertThat(logicalCondition.count()).isEqualTo(2)
//        assertThat(logicalCondition.leftOperand).isEqualTo()
//        assertThat(logicalCondition.operator).isEqualTo(Operator.)
//        assertThat(logicalCondition.leftOperand).isEqualTo()

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

    @Test
    fun FungibleAssetQueryCriteria() {

        val owners = listOf(MINI_CORP.name)
        val criteria1 = QueryCriteria.FungibleAssetQueryCriteria(ownerIdentity = owners)
        criteriaParse.parse(criteria1)

        val tokenTypes = listOf(Currency::class.java)
        val criteria2 = QueryCriteria.FungibleAssetQueryCriteria(tokenType = tokenTypes)
        criteriaParse.parse(criteria2)

        val tokenValues = listOf(GBP.currencyCode, USD.currencyCode)
        val criteria3 = QueryCriteria.FungibleAssetQueryCriteria(tokenValue = tokenValues)
        criteriaParse.parse(criteria3)

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
        val criteria7 = QueryCriteria.FungibleAssetQueryCriteria(exitKeyIdentity = exitKeyIds)
        criteriaParse.parse(criteria7)

        val criteriaAnd = criteria2.and(criteria3)
        criteriaParse.parse(criteriaAnd)

        val criteriaOr = criteria1.or(criteria4)
        criteriaParse.parse(criteriaOr)

        fail()
    }

    /** Commercial Paper V1 (Hibernate JPA */
    @Test
    fun VaultCustomQueryCriteriaSingleExpression() {

        // Commercial Paper
        val expression = LogicalExpression(CommercialPaperSchemaV1.PersistentCommercialPaperState::currency, Operator.EQUAL, USD.currencyCode)
        val criteria1 = QueryCriteria.VaultCustomQueryCriteria(expression)
        criteriaParse.parse(criteria1)

        fail()
    }

    /** Commercial Paper V2 (Requery JPA) */
    @Test
    fun VaultCustomQueryCriteriaSingleExpressionJPA() {

        // Commercial Paper
        val expression = LogicalExpression(CommercialPaperSchemaV2.PersistentCommercialPaperState2::currency, Operator.EQUAL, USD.currencyCode)
        val criteria1 = QueryCriteria.VaultCustomQueryCriteria(expression)
        criteriaParse.parse(criteria1)

        fail()
    }

    /** Commercial Paper V3 (Requery Kotlin interface class) */
    @Test
    fun VaultCustomQueryCriteriaSingleExpressionInterface() {

        // Commercial Paper
//        val expression = LogicalExpression(VaultSchema.VaultStates::stateStatus, Operator.EQUAL, Vault.StateStatus.UNCONSUMED)
        val expression = LogicalExpression(CommercialPaperSchemaV3.PersistentCommercialPaperState3::currency, Operator.EQUAL, USD.currencyCode)
        val attribute = findAttribute(expression.leftOperand)

        val criteria1 = QueryCriteria.VaultCustomQueryCriteria(expression)
        criteria1?.let {
            it.indexExpression?.let {
                it.operator
                it.leftOperand
                it.rightOperand
            }
        }
        val property = criteria1.indexExpression?.leftOperand!!
        val attribute2 = findAttribute(property)

        criteriaParse.parseMe(criteria1)
        criteriaParse.parseMe2(expression)

        val requeryExpr = criteriaParse.parseCriteria(criteria1)
        assertThat(requeryExpr.operator).isEqualTo(io.requery.query.Operator.EQUAL)

    }

    /** Commercial Paper V4 (Requery Kotlin data class) */
    @Test
    fun VaultCustomQueryCriteriaSingleExpressionDataClass() {

        // Commercial Paper
        val expression = LogicalExpression(CommercialPaperSchemaV4.PersistentCommercialPaperState4::currency, Operator.EQUAL, USD.currencyCode)
        val criteria1 = QueryCriteria.VaultCustomQueryCriteria(expression)
        criteriaParse.parse(criteria1)

        fail()
    }

    @Test
    fun VaultCustomQueryCriteriaCombinedExpressions() {

        // Commercial Paper
        val ccyExpr = LogicalExpression(CommercialPaperSchemaV1.PersistentCommercialPaperState::currency, Operator.EQUAL, USD.currencyCode)
        val maturityExpr = LogicalExpression(CommercialPaperSchemaV1.PersistentCommercialPaperState::maturity, Operator.GREATER_THAN_OR_EQUAL, TEST_TX_TIME + 30.days)
        val faceValueExpr = LogicalExpression(CommercialPaperSchemaV1.PersistentCommercialPaperState::faceValue, Operator.GREATER_THAN_OR_EQUAL, 10000)
        val combinedExpr = maturityExpr.and(faceValueExpr).or(ccyExpr)
        val criteria1 = QueryCriteria.VaultCustomQueryCriteria(indexExpression = combinedExpr)
        criteriaParse.parse(criteria1)

        fail()
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