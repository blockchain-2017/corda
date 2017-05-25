package net.corda.node.services.vault

import net.corda.contracts.CommercialPaper
import net.corda.contracts.asset.Cash
import net.corda.contracts.asset.DUMMY_CASH_ISSUER
import net.corda.contracts.testing.*
import net.corda.core.contracts.*
import net.corda.core.crypto.entropyToKeyPair
import net.corda.core.days
import net.corda.core.identity.Party
import net.corda.core.node.services.*
import net.corda.core.node.services.vault.*
import net.corda.core.node.services.vault.QueryCriteria.*
import net.corda.core.seconds
import net.corda.core.serialization.OpaqueBytes
import net.corda.core.utilities.*
import net.corda.node.services.vault.schemas.jpa.CommonSchemaV1
import net.corda.node.utilities.configureDatabase
import net.corda.node.utilities.transaction
import net.corda.schemas.CashSchemaV1.PersistentCashState
import net.corda.schemas.CommercialPaperSchemaV2
import net.corda.testing.*
import net.corda.testing.node.MockServices
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.asn1.x500.X500Name
import org.jetbrains.exposed.sql.Database
import org.junit.Ignore
import org.junit.Test
import java.io.Closeable
import java.lang.Thread.sleep
import java.math.BigInteger
import java.security.KeyPair
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.test.assertFails

abstract class VaultQueryTests {

    lateinit var services: MockServices
    val vaultSvc: VaultService get() = services.vaultService
    val vaultQuerySvc: VaultQueryService get() = services.vaultQueryService
    lateinit var dataSource: Closeable
    lateinit var database: Database

    @Ignore //@Test
    fun createPersistentTestDb() {

        val dataSourceAndDatabase = configureDatabase(makePersistentDataSourceProperties())
        val dataSource = dataSourceAndDatabase.first
        val database = dataSourceAndDatabase.second

        setUpDb(database, 5000)

        dataSource.close()
    }

    private fun setUpDb(_database: Database, delay: Long = 0) {

        _database.transaction {

            // create new states
            services.fillWithSomeTestCash(100.DOLLARS, CASH_NOTARY, 10, 10, Random(0L))
            val linearStatesXYZ = services.fillWithSomeTestLinearStates(1, "XYZ")
            val linearStatesJKL = services.fillWithSomeTestLinearStates(2, "JKL")
            services.fillWithSomeTestLinearStates(3, "ABC")
            val dealStates = services.fillWithSomeTestDeals(listOf("123", "456", "789"))

            // Total unconsumed states = 10 + 1 + 2 + 3 + 3 = 19

            sleep(delay)

            // consume some states
            services.consumeLinearStates(linearStatesXYZ.states.toList())
            services.consumeLinearStates(linearStatesJKL.states.toList())
            services.consumeDeals(dealStates.states.filter { it.state.data.ref == "456" })
            services.consumeCash(50.DOLLARS)

            // Total unconsumed states = 4 + 3 + 2 + 1 (new cash change) = 10
            // Total unconsumed states = 6 + 1 + 2 + 1 = 10
        }
    }

    private fun makePersistentDataSourceProperties(): Properties {
        val props = Properties()
        props.setProperty("dataSourceClassName", "org.h2.jdbcx.JdbcDataSource")
        props.setProperty("dataSource.url", "jdbc:h2:~/test/vault_query_persistence;DB_CLOSE_ON_EXIT=TRUE")
        props.setProperty("dataSource.user", "sa")
        props.setProperty("dataSource.password", "")
        return props
    }

    /**
     * Query API tests
     */

    /** Generic Query tests
    (combining both FungibleState and LinearState contract types) */

    @Test
    fun `unconsumed states simple`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 3, 3, Random(0L))
            services.fillWithSomeTestLinearStates(10)
            services.fillWithSomeTestDeals(listOf("123", "456", "789"))

            // DOCSTART VaultQueryExample1
            val result = vaultQuerySvc.queryBy<ContractState>()

            /**
             * Query result returns a [Vault.Page] which contains:
             *  1) actual states as a list of [StateAndRef]
             *  2) state reference and associated vault metadata as a list of [Vault.StateMetadata]
             *  3) [PageSpecification] used to delimit the size of items returned in the result set (defaults to [DEFAULT_PAGE_SIZE])
             *  4) Total number of items available (to aid further pagination if required)
             */
            val states = result.states
            val metadata = result.statesMetadata

            // DOCEND VaultQueryExample1
            assertThat(states).hasSize(16)
            assertThat(metadata).hasSize(16)
        }
    }

    @Test
    fun `unconsumed states verbose`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 3, 3, Random(0L))
            services.fillWithSomeTestLinearStates(10)
            services.fillWithSomeTestDeals(listOf("123", "456", "789"))

            val criteria = VaultQueryCriteria() // default is UNCONSUMED
            val result = vaultQuerySvc.queryBy<ContractState>(criteria)

            assertThat(result.states).hasSize(16)
            assertThat(result.statesMetadata).hasSize(16)
        }
    }

    @Test
    fun `unconsumed cash states simple`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 3, 3, Random(0L))
            services.fillWithSomeTestLinearStates(10)
            services.fillWithSomeTestDeals(listOf("123", "456", "789"))

            val result = vaultQuerySvc.queryBy<Cash.State>()

            assertThat(result.states).hasSize(3)
            assertThat(result.statesMetadata).hasSize(3)
        }
    }

    @Test
    fun `unconsumed cash states verbose`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 3, 3, Random(0L))
            services.fillWithSomeTestLinearStates(10)
            services.fillWithSomeTestDeals(listOf("123", "456", "789"))

            val criteria = VaultQueryCriteria() // default is UNCONSUMED
            val result = vaultQuerySvc.queryBy<Cash.State>(criteria)

            assertThat(result.states).hasSize(3)
            assertThat(result.statesMetadata).hasSize(3)
        }
    }

    @Test
    fun `unconsumed states for state refs`() {
        database.transaction {

            val issuedStates = services.fillWithSomeTestLinearStates(2)
            val stateRefs = issuedStates.states.map { it.ref }.toList()
            services.fillWithSomeTestLinearStates(8)

            // DOCSTART VaultQueryExample2
            val criteria = VaultQueryCriteria(stateRefs = listOf(stateRefs.first(), stateRefs.last()))
//            val results = vaultQuerySvc.queryBy<LinearState>(criteria)
            val results = vaultQuerySvc.queryBy<DummyLinearContract.State>(criteria)
            // DOCEND VaultQueryExample2

            assertThat(results.states).hasSize(2)
            assertThat(results.states.first().ref).isEqualTo(issuedStates.states.first().ref)
            assertThat(results.states.last().ref).isEqualTo(issuedStates.states.last().ref)
        }
    }

    @Test
    fun `unconsumed states for contract state types`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 3, 3, Random(0L))
            services.fillWithSomeTestLinearStates(10)
            services.fillWithSomeTestDeals(listOf("123", "456", "789"))

            // default State.Status is UNCONSUMED
            // DOCSTART VaultQueryExample3
//            val criteria = VaultQueryCriteria(contractStateTypes = setOf(Cash.State::class.java, DealState::class.java))
            val criteria = VaultQueryCriteria(contractStateTypes = setOf(Cash.State::class.java, DummyDealContract.State::class.java))
            val results = vaultQuerySvc.queryBy<ContractState>(criteria)
            // DOCEND VaultQueryExample3
            assertThat(results.states).hasSize(6)
        }
    }

    @Test
    fun `consumed states`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 3, 3, Random(0L))
            val linearStates = services.fillWithSomeTestLinearStates(2, "TEST") // create 2 states with same externalId
            services.fillWithSomeTestLinearStates(8)
            val dealStates = services.fillWithSomeTestDeals(listOf("123", "456", "789"))

            services.consumeLinearStates(linearStates.states.toList())
            services.consumeDeals(dealStates.states.filter { it.state.data.ref == "456" })
            services.consumeCash(50.DOLLARS)

            val criteria = VaultQueryCriteria(status = Vault.StateStatus.CONSUMED)
            val results = vaultQuerySvc.queryBy<ContractState>(criteria)
            assertThat(results.states).hasSize(5)
        }
    }

    @Test
    fun `all states`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 3, 3, Random(0L))
            val linearStates = services.fillWithSomeTestLinearStates(2, "TEST") // create 2 results with same UID
            services.fillWithSomeTestLinearStates(8)
            val dealStates = services.fillWithSomeTestDeals(listOf("123", "456", "789"))

            services.consumeLinearStates(linearStates.states.toList())
            services.consumeDeals(dealStates.states.filter { it.state.data.ref == "456" })
            services.consumeCash(50.DOLLARS) // generates a new change state!

            val criteria = VaultQueryCriteria(status = Vault.StateStatus.ALL)
            val results = vaultQuerySvc.queryBy<ContractState>(criteria)
            assertThat(results.states).hasSize(17)
        }
    }


    val CASH_NOTARY_KEY: KeyPair by lazy { entropyToKeyPair(BigInteger.valueOf(20)) }
    val CASH_NOTARY: Party get() = Party(X500Name("CN=Cash Notary Service,O=R3,OU=corda,L=Zurich,C=CH"), CASH_NOTARY_KEY.public)

    @Test
    fun `unconsumed states by notary`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, CASH_NOTARY, 3, 3, Random(0L))
            services.fillWithSomeTestLinearStates(10)
            services.fillWithSomeTestDeals(listOf("123", "456", "789"))

            // DOCSTART VaultQueryExample4
            val criteria = VaultQueryCriteria(notaryName = listOf(CASH_NOTARY.name))
            val results = vaultQuerySvc.queryBy<ContractState>(criteria)
            // DOCEND VaultQueryExample4
            assertThat(results.states).hasSize(3)
        }
    }

    @Test(expected = UnsupportedQueryException::class)
    fun `unconsumed states by participants`() {
        database.transaction {

            services.fillWithSomeTestLinearStates(2, "TEST", participants = listOf(MEGA_CORP_PUBKEY, MINI_CORP_PUBKEY))
            services.fillWithSomeTestDeals(listOf("456"), parties = listOf(MEGA_CORP.toAnonymous(), BIG_CORP.toAnonymous()))
            services.fillWithSomeTestDeals(listOf("123", "789"), parties = listOf(BIG_CORP.toAnonymous(), MINI_CORP.toAnonymous()))

            // DOCSTART VaultQueryExample5
            val criteria = VaultQueryCriteria(participantIdentities = listOf(MEGA_CORP.name, MINI_CORP.name))
            val results = vaultQuerySvc.queryBy<ContractState>(criteria)
            // DOCEND VaultQueryExample5

            assertThat(results.states).hasSize(3)
        }
    }

    @Test
    fun `unconsumed states excluding soft locks`() {
        database.transaction {

            val issuedStates = services.fillWithSomeTestCash(100.DOLLARS, CASH_NOTARY, 3, 3, Random(0L))
            vaultSvc.softLockReserve(UUID.randomUUID(), setOf(issuedStates.states.first().ref, issuedStates.states.last().ref))

            val criteria = VaultQueryCriteria(includeSoftlockedStates = false)
            val results = vaultQuerySvc.queryBy<ContractState>(criteria)
            assertThat(results.states).hasSize(1)
        }
    }

    private val TODAY = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC)

    @Test
    fun `unconsumed states recorded between two time intervals`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, CASH_NOTARY, 3, 3, Random(0L))

            // DOCSTART VaultQueryExample6
            val start = TODAY
            val end = TODAY.plus(30, ChronoUnit.DAYS)
            val recordedBetweenExpression = LogicalExpression(
                    QueryCriteria.TimeInstantType.RECORDED, Operator.BETWEEN, arrayOf(start, end))
            val criteria = VaultQueryCriteria(timeCondition = recordedBetweenExpression)
            val results = vaultQuerySvc.queryBy<ContractState>(criteria)
            // DOCEND VaultQueryExample6
            assertThat(results.states).hasSize(3)

            // Future
            val startFuture = TODAY.plus(1, ChronoUnit.DAYS)
            val recordedBetweenExpressionFuture = LogicalExpression(
                    QueryCriteria.TimeInstantType.RECORDED, Operator.BETWEEN, arrayOf(startFuture, end))
            val criteriaFuture = VaultQueryCriteria(timeCondition = recordedBetweenExpressionFuture)
            assertThat(vaultQuerySvc.queryBy<ContractState>(criteriaFuture).states).isEmpty()
        }
    }

    @Test
    fun `states consumed after time`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, CASH_NOTARY, 3, 3, Random(0L))
            services.fillWithSomeTestLinearStates(10)
            services.fillWithSomeTestDeals(listOf("123", "456", "789"))

            services.consumeCash(100.DOLLARS)

            val asOfDateTime = TODAY
            val consumedAfterExpression = LogicalExpression(
                    QueryCriteria.TimeInstantType.CONSUMED, Operator.GREATER_THAN_OR_EQUAL, arrayOf(asOfDateTime))
            val criteria = VaultQueryCriteria(status = Vault.StateStatus.CONSUMED,
                                              timeCondition = consumedAfterExpression)
            val results = vaultQuerySvc.queryBy<ContractState>(criteria)

            assertThat(results.states).hasSize(3)
        }
    }

    // pagination: first page
    @Test
    fun `all states with paging specification - first page`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 100, 100, Random(0L))

            // DOCSTART VaultQueryExample7
            val pagingSpec = PageSpecification(DEFAULT_PAGE_NUM, 10)
            val criteria = VaultQueryCriteria(status = Vault.StateStatus.ALL)
            val results = vaultQuerySvc.queryBy<ContractState>(criteria, paging = pagingSpec)
            // DOCEND VaultQueryExample7
            assertThat(results.states).hasSize(10)
            assertThat(results.totalStatesAvailable).isEqualTo(100)
        }
    }

    // pagination: last page
    @Test
    fun `all states with paging specification  - last`() {
        database.transaction {

            services.fillWithSomeTestCash(95.DOLLARS, DUMMY_NOTARY, 95, 95, Random(0L))

            // Last page implies we need to perform a row count for the Query first,
            // and then re-query for a given offset defined by (count - pageSize)
            val pagingSpec = PageSpecification(9, 10)

            val criteria = VaultQueryCriteria(status = Vault.StateStatus.ALL)
            val results = vaultQuerySvc.queryBy<ContractState>(criteria, paging = pagingSpec)
            assertThat(results.states).hasSize(5) // should retrieve states 90..94
            assertThat(results.totalStatesAvailable).isEqualTo(95)
        }
    }

    // pagination: invalid page number
    @Test(expected = VaultQueryException::class)
    fun `invalid page number`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 100, 100, Random(0L))

            val pagingSpec = PageSpecification(-1, 10)

            val criteria = VaultQueryCriteria(status = Vault.StateStatus.ALL)
            val results = vaultQuerySvc.queryBy<ContractState>(criteria, paging = pagingSpec)
            assertThat(results.states).hasSize(10) // should retrieve states 90..99
        }
    }

    // pagination: invalid page size
    @Test(expected = VaultQueryException::class)
    fun `invalid page size`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 100, 100, Random(0L))

            val pagingSpec = PageSpecification(0, MAX_PAGE_SIZE+1)

            val criteria = VaultQueryCriteria(status = Vault.StateStatus.ALL)
            vaultQuerySvc.queryBy<ContractState>(criteria, paging = pagingSpec)
            assertFails {  }
        }
    }

    // pagination: out or range request (page number * page size) > total rows available
    @Test(expected = VaultQueryException::class)
    fun `out of range page request`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 100, 100, Random(0L))

            val pagingSpec = PageSpecification(10, 10)  // this requests results 101 .. 110

            val criteria = VaultQueryCriteria(status = Vault.StateStatus.ALL)
            val results = vaultQuerySvc.queryBy<ContractState>(criteria, paging = pagingSpec)
            assertFails { println("Query should throw an exception [${results.states.count()}]") }
        }
    }

    // sorting
    @Test
    fun `all states sorted by contract type, state status, consumed time`() {

        setUpDb(database)

        database.transaction {

//            val sorting = Sort(setOf(Sort.SortColumn(VaultSchema.VaultStates::contractStateClassName.name, Sort.Direction.DESC)))
            val sortCol1 = Sort.SortColumn("contract_state_class_name", Sort.Direction.DESC)
            val sortCol2 = Sort.SortColumn("state_status", Sort.Direction.ASC)
            val sortCol3 = Sort.SortColumn("consumed_timestamp", Sort.Direction.DESC, Sort.NullHandling.NULLS_LAST)
            val sorting = Sort(setOf(sortCol1, sortCol2, sortCol3))
            val result = vaultQuerySvc.queryBy<ContractState>(VaultQueryCriteria(status = Vault.StateStatus.ALL), sorting = sorting)

            val states = result.states
            val metadata = result.statesMetadata

            for (i in 0..states.size-1) {
                println("${states[i].ref} : ${metadata[i].contractStateClassName}, ${metadata[i].status}, ${metadata[i].consumedTime}")
//                println("${states[i].ref} : ${states[i].state.data})")
//                println("${states[i].ref} : ${metadata[i]})")
            }

//            states.forEach { println("${it.ref} : ${it.state})") }

            assertThat(states).hasSize(20)
            assertThat(metadata.first().contractStateClassName).isEqualTo("net.corda.contracts.testing.DummyLinearContract\$State")
            assertThat(metadata.first().status).isEqualTo(Vault.StateStatus.UNCONSUMED) // 0 = UNCONSUMED
            assertThat(metadata.last().contractStateClassName).isEqualTo("net.corda.contracts.asset.Cash\$State")
            assertThat(metadata.last().status).isEqualTo(Vault.StateStatus.CONSUMED)    // 1 = CONSUMED
        }
    }

    @Test
    fun `unconsumed fungible assets`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 3, 3, Random(0L))
//            services.fillWithSomeTestCommodity()
            services.fillWithSomeTestLinearStates(10)

            val results = vaultQuerySvc.queryBy<FungibleAsset<*>>()
            assertThat(results.states).hasSize(3)
        }
    }

    @Test
    fun `consumed fungible assets`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 3, 3, Random(0L))
            services.consumeCash(50.DOLLARS)
//            services.fillWithSomeTestCommodity()
//            services.consumeCommodity()
            services.fillWithSomeTestLinearStates(10)

            val criteria = VaultQueryCriteria(status = Vault.StateStatus.CONSUMED)
            val results = vaultQuerySvc.queryBy<FungibleAsset<*>>(criteria)
            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `unconsumed cash fungible assets`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 3, 3, Random(0L))
            services.fillWithSomeTestLinearStates(10)

            val results = vaultQuerySvc.queryBy<Cash.State>()
            assertThat(results.states).hasSize(3)
        }
    }

    @Test
    fun `consumed cash fungible assets`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 3, 3, Random(0L))
            services.consumeCash(50.DOLLARS)
            services.fillWithSomeTestLinearStates(10)
//          services.consumeLinearStates(8)

            val criteria = VaultQueryCriteria(status = Vault.StateStatus.CONSUMED)
            val results = vaultQuerySvc.queryBy<Cash.State>(criteria)
            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `unconsumed linear heads`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 3, 3, Random(0L))
            services.fillWithSomeTestLinearStates(10)
            services.fillWithSomeTestDeals(listOf("123", "456", "789"))

            val results = vaultQuerySvc.queryBy<LinearState>()
            assertThat(results.states).hasSize(13)
        }
    }

    @Test
    fun `consumed linear heads`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 3, 3, Random(0L))
            val linearStates = services.fillWithSomeTestLinearStates(2, "TEST") // create 2 states with same externalId
            services.fillWithSomeTestLinearStates(8)
            val dealStates = services.fillWithSomeTestDeals(listOf("123", "456", "789"))

            services.consumeLinearStates(linearStates.states.toList())
            services.consumeDeals(dealStates.states.filter { it.state.data.ref == "456" })
            services.consumeCash(50.DOLLARS)

            val criteria = VaultQueryCriteria(status = Vault.StateStatus.CONSUMED)
            val results = vaultQuerySvc.queryBy<LinearState>(criteria)
            assertThat(results.states).hasSize(3)
        }
    }

    /** LinearState tests */

    @Test
    fun `unconsumed linear heads for linearId`() {
        database.transaction {

            val issuedStates = services.fillWithSomeTestLinearStates(10)

            // DOCSTART VaultQueryExample8
            val linearIds = issuedStates.states.map { it.state.data.linearId }.toList()
            val criteria = LinearStateQueryCriteria(linearId = listOf(linearIds.first(), linearIds.last()))
            val results = vaultQuerySvc.queryBy<LinearState>(criteria)
            // DOCEND VaultQueryExample8
            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `return chain of linear state for a given id`() {
        database.transaction {

            val txns = services.fillWithSomeTestLinearStates(1, "TEST")
//            services.processLinearState(id)  // consume current and produce new state reference
//            services.processLinearState(id)  // consume current and produce new state reference
//            services.processLinearState(id)  // consume current and produce new state reference

            // should now have 1 UNCONSUMED & 3 CONSUMED state refs for Linear State with "TEST"
            // DOCSTART VaultQueryExample9
            val linearStateCriteria = LinearStateQueryCriteria(linearId = txns.states.map { it.state.data.linearId })
            val vaultCriteria = VaultQueryCriteria(status = Vault.StateStatus.ALL)
            val sorting = Sort(setOf(Sort.SortColumn("uuid", Sort.Direction.DESC)))     // Note: column name (not attribute name)
            val results = vaultQuerySvc.queryBy<LinearState>(linearStateCriteria.and(vaultCriteria), sorting = sorting)
            // DOCEND VaultQueryExample9
            assertThat(results.states).hasSize(4)
        }
    }

    @Test
    fun `DEPRECATED return linear states for a given id`() {
        database.transaction {

            val txns = services.fillWithSomeTestLinearStates(1, "TEST")
//            services.processLinearState(id)  // consume current and produce new state reference
//            services.processLinearState(id)  // consume current and produce new state reference
//            services.processLinearState(id)  // consume current and produce new state reference

            // should now have 1 UNCONSUMED & 3 CONSUMED state refs for Linear State with "TEST"

            // DOCSTART VaultDeprecatedQueryExample1
            val states = vaultSvc.linearHeadsOfType<LinearState>().filter { it.key == txns.states.first().state.data.linearId }
            // DOCEND VaultDeprecatedQueryExample1

            assertThat(states).hasSize(4)
        }
    }

    @Test
    fun `DEPRECATED return consumed linear states for a given id`() {
        database.transaction {

            val txns = services.fillWithSomeTestLinearStates(1, "TEST")
//            services.processLinearState(id)  // consume current and produce new state reference
//            services.processLinearState(id)  // consume current and produce new state reference
//            services.processLinearState(id)  // consume current and produce new state reference

            // should now have 1 UNCONSUMED & 3 CONSUMED state refs for Linear State with "TEST"

            // DOCSTART VaultDeprecatedQueryExample2
            val states = vaultSvc.states(setOf(LinearState::class.java),
                                         EnumSet.of(Vault.StateStatus.CONSUMED)).filter { it.state.data.linearId == txns.states.first().state.data.linearId }
            // DOCEND VaultDeprecatedQueryExample2

            assertThat(states).hasSize(3)
        }
    }

//    @Test
//    fun `latest unconsumed linear heads for state refs`() {
//        database.transaction {
//
//            val issuedStates = services.fillWithSomeTestLinearStates(2, "TEST") // create 2 states with same UID
//            services.fillWithSomeTestLinearStates(8)
//            val stateRefs = issuedStates.states.map { it.ref }.toList()
//
//            val vaultCriteria = VaultQueryCriteria(stateRefs = listOf(stateRefs.first(), stateRefs.last()))
//            val linearStateCriteria = LinearStateQueryCriteria(latestOnly = true)
//            val results = vaultQuerySvc.queryBy<LinearState>(vaultCriteria.and(linearStateCriteria))
//            assertThat(results.states).hasSize(2)
//        }
//    }

    @Test
    fun `unconsumed deals`() {
        database.transaction {

            services.fillWithSomeTestDeals(listOf("123", "456", "789"))

            val results = vaultQuerySvc.queryBy<DealState>()
            assertThat(results.states).hasSize(3)
        }
    }

    @Test
    fun `unconsumed deals for ref`() {
        database.transaction {

            services.fillWithSomeTestDeals(listOf("123", "456", "789"))

            // DOCSTART VaultQueryExample10
            val criteria = LinearStateQueryCriteria(dealRef = listOf("456", "789"))
            val results = vaultQuerySvc.queryBy<DealState>(criteria)
            // DOCEND VaultQueryExample10

            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `latest unconsumed deals for ref`() {
        database.transaction {

            services.fillWithSomeTestLinearStates(2, "TEST")
            services.fillWithSomeTestDeals(listOf("456"))
            services.fillWithSomeTestDeals(listOf("123", "789"))

            val criteria = LinearStateQueryCriteria(dealRef = listOf("456"))
            val results = vaultQuerySvc.queryBy<DealState>(criteria)
            assertThat(results.states).hasSize(1)
        }
    }

    @Test
    fun `latest unconsumed deals with party`() {
        database.transaction {

            services.fillWithSomeTestLinearStates(2, "TEST")
            services.fillWithSomeTestDeals(listOf("456"))        // specify party
            services.fillWithSomeTestDeals(listOf("123", "789"))

            // DOCSTART VaultQueryExample11
            val criteria = LinearStateQueryCriteria(dealPartyName = listOf(MEGA_CORP.name, MINI_CORP.name))
            val results = vaultQuerySvc.queryBy<DealState>(criteria)
            // DOCEND VaultQueryExample11

            assertThat(results.states).hasSize(1)
        }
    }

    /** FungibleAsset tests */
    @Test
    fun `unconsumed fungible assets of token type`() {
        database.transaction {

            services.fillWithSomeTestLinearStates(10)
            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 3, 3, Random(0L))
            services.fillWithSomeTestCash(100.POUNDS, DUMMY_NOTARY, 3, 3, Random(0L))
            services.fillWithSomeTestCash(100.SWISS_FRANCS, DUMMY_NOTARY, 3, 3, Random(0L))

            val criteria = FungibleAssetQueryCriteria(tokenType = listOf(Currency::class.java))
            val results = vaultQuerySvc.queryBy<FungibleAsset<*>>(criteria)
            assertThat(results.states).hasSize(9)
        }
    }

    @Test
    fun `unconsumed fungible assets for single currency`() {
        database.transaction {

            services.fillWithSomeTestLinearStates(10)
            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 3, 3, Random(0L))
            services.fillWithSomeTestCash(100.POUNDS, DUMMY_NOTARY, 3, 3, Random(0L))
            services.fillWithSomeTestCash(100.SWISS_FRANCS, DUMMY_NOTARY, 3, 3, Random(0L))

            // DOCSTART VaultQueryExample12
            val criteria = FungibleAssetQueryCriteria(tokenValue = listOf(USD.currencyCode))
            val results = vaultQuerySvc.queryBy<FungibleAsset<*>>(criteria)
            // DOCEND VaultQueryExample12

            assertThat(results.states).hasSize(3)
        }
    }

    @Test
    fun `unconsumed fungible assets for single currency and quantity greater than`() {
        database.transaction {

            services.fillWithSomeTestLinearStates(10)
            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 3, 3, Random(0L))
            services.fillWithSomeTestCash(100.POUNDS, DUMMY_NOTARY, 1, 1, Random(0L))
            services.fillWithSomeTestCash(50.POUNDS, DUMMY_NOTARY, 1, 1, Random(0L))
            services.fillWithSomeTestCash(100.SWISS_FRANCS, DUMMY_NOTARY, 3, 3, Random(0L))

            // DOCSTART VaultQueryExample13
            val criteria = FungibleAssetQueryCriteria(tokenValue = listOf(GBP.currencyCode),
                                                      quantity = LogicalExpression(this, Operator.GREATER_THAN, 50))
            val results = vaultQuerySvc.queryBy<Cash.State>(criteria)
            // DOCEND VaultQueryExample13

            assertThat(results.states).hasSize(1)
        }
    }

    @Test
    fun `unconsumed fungible assets for several currencies`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 3, 3, Random(0L))
            services.fillWithSomeTestCash(100.POUNDS, DUMMY_NOTARY, 3, 3, Random(0L))
            services.fillWithSomeTestCash(100.SWISS_FRANCS, DUMMY_NOTARY, 3, 3, Random(0L))

            val criteria = FungibleAssetQueryCriteria(tokenValue = listOf(CHF.currencyCode, GBP.currencyCode))
            val results = vaultQuerySvc.queryBy<FungibleAsset<*>>(criteria)
            assertThat(results.states).hasSize(3)
        }
    }

    @Test
    fun `unconsumed fungible assets for issuer party`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 1, 1, Random(0L), issuedBy = (DUMMY_CASH_ISSUER))
            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 1, 1, Random(0L), issuedBy = (BOC.ref(1)), issuerKey = BOC_KEY)

            // DOCSTART VaultQueryExample14
            val criteria = FungibleAssetQueryCriteria(issuerPartyName = listOf(BOC.name))
            val results = vaultQuerySvc.queryBy<FungibleAsset<*>>(criteria)
            // DOCEND VaultQueryExample14

            assertThat(results.states).hasSize(1)
        }
    }

    @Test
    fun `unconsumed fungible assets for specific issuer party and refs`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 1, 1, Random(0L), issuedBy = (DUMMY_CASH_ISSUER))
            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 1, 1, Random(0L), issuedBy = (BOC.ref(1)), issuerKey = BOC_KEY, ref = OpaqueBytes.of(1))
            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 1, 1, Random(0L), issuedBy = (BOC.ref(2)), issuerKey = BOC_KEY, ref = OpaqueBytes.of(2))
            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 1, 1, Random(0L), issuedBy = (BOC.ref(3)), issuerKey = BOC_KEY, ref = OpaqueBytes.of(3))

            val criteria = FungibleAssetQueryCriteria(issuerPartyName = listOf(BOC.name),
                                                      issuerRef = listOf(BOC.ref(1).reference, BOC.ref(2).reference))
            val results = vaultQuerySvc.queryBy<FungibleAsset<*>>(criteria)
            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `unconsumed fungible assets with exit keys`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 1, 1, Random(0L), issuedBy = (DUMMY_CASH_ISSUER))
            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 1, 1, Random(0L), issuedBy = (BOC.ref(1)), issuerKey = BOC_KEY)

            // DOCSTART VaultQueryExample15
            val exitIds = getTestX509Name("TEST")
//                    if (DUMMY_CASH_ISSUER.party.nameOrNull() == null)
//                        getTestX509Name("TEST")
//                    else DUMMY_CASH_ISSUER.party.nameOrNull()

            val criteria = FungibleAssetQueryCriteria(exitKeyIdentity = listOf(exitIds))
            val results = vaultQuerySvc.queryBy<FungibleAsset<*>>(criteria)
            // DOCEND VaultQueryExample15

            assertThat(results.states).hasSize(1)
        }
    }

    @Test
    fun `unconsumed fungible assets by owner`() {
        database.transaction {

            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 2, 2, Random(0L), issuedBy = (DUMMY_CASH_ISSUER))
            // issue some cash to BOB
            // issue some cash to ALICE

            val criteria = FungibleAssetQueryCriteria(ownerIdentity = listOf(BOB.name, ALICE.name))
            val results = vaultQuerySvc.queryBy<FungibleAsset<*>>(criteria)
            assertThat(results.states).hasSize(1)
        }
    }

    /** Vault Custom Query tests */

    // specifying Query on Commercial Paper contract state attributes
    @Test
    fun `commercial paper custom query`() {
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

            val ccyIndex = LogicalExpression(CommercialPaperSchemaV2.PersistentCommercialPaperState::currency, Operator.EQUAL, USD.currencyCode)
            val maturityIndex = LogicalExpression(CommercialPaperSchemaV2.PersistentCommercialPaperState::maturity, Operator.GREATER_THAN_OR_EQUAL, TEST_TX_TIME + 30.days)
            val faceValueIndex = LogicalExpression(CommercialPaperSchemaV2.PersistentCommercialPaperState::faceValue, Operator.GREATER_THAN_OR_EQUAL, 10000L)

            val criteria1 = VaultCustomQueryCriteria(ccyIndex)
            val criteria2 = VaultCustomQueryCriteria(maturityIndex)
            val criteria3 = VaultCustomQueryCriteria(faceValueIndex)

            val result = vaultQuerySvc.queryBy<CommercialPaper.State>(criteria1.and(criteria3).or(criteria2))

            assertThat(result.states).hasSize(1)
            assertThat(result.statesMetadata).hasSize(1)
        }
    }

    /** Chaining together different Query Criteria tests**/

    // specifying Query on Cash contract state attributes
    @Test
    fun `all cash states with amount of currency greater or equal than`() {

        database.transaction {

            services.fillWithSomeTestCash(100.POUNDS, DUMMY_NOTARY, 1, 1, Random(0L))
            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 1, 1, Random(0L))
            services.fillWithSomeTestCash(10.DOLLARS, DUMMY_NOTARY, 1, 1, Random(0L))
            services.fillWithSomeTestCash(1.DOLLARS, DUMMY_NOTARY, 1, 1, Random(0L))

            // DOCSTART VaultQueryExample16
            val generalCriteria = VaultQueryCriteria(Vault.StateStatus.ALL)

            val currencyIndex = LogicalExpression(PersistentCashState::currency, Operator.EQUAL, USD.currencyCode)
            val quantityIndex = LogicalExpression(PersistentCashState::pennies, Operator.GREATER_THAN_OR_EQUAL, 10L)

            val customCriteria1 = VaultCustomQueryCriteria(currencyIndex)
            val customCriteria2 = VaultCustomQueryCriteria(quantityIndex)

            val criteria = generalCriteria.and(customCriteria1.and(customCriteria2))
            val results = vaultQuerySvc.queryBy<Cash.State>(criteria)
            // DOCEND VaultQueryExample16

            assertThat(results.states).hasSize(2)
        }
    }

    // specifying Query on Linear state attributes
    @Test
    fun `consumed linear heads for linearId between two timestamps`() {
        database.transaction {
            val issuedStates = services.fillWithSomeTestLinearStates(10)
            val externalIds = issuedStates.states.map { it.state.data.linearId }.map { it.externalId }[0]
            val uuids = issuedStates.states.map { it.state.data.linearId }.map { it.id }[1]

            val start = TEST_TX_TIME
            val end = TEST_TX_TIME.plus(30, ChronoUnit.DAYS)
            val recordedBetweenExpression = LogicalExpression(TimeInstantType.RECORDED, Operator.BETWEEN, arrayOf(start, end))
            val basicCriteria = VaultQueryCriteria(timeCondition = recordedBetweenExpression)

            val linearIdsExpression =
                    if (externalIds == null)
                        LogicalExpression(CommonSchemaV1.LinearState::externalId, Operator.IS_NULL, null)
                    else
                        LogicalExpression(CommonSchemaV1.LinearState::externalId, Operator.IN, externalIds)
            val linearIdCondition = LogicalExpression(CommonSchemaV1.LinearState::uuid, Operator.EQUAL, uuids)

            val customIndexCriteria1 = VaultCustomQueryCriteria(linearIdsExpression)
            val customIndexCriteria2 = VaultCustomQueryCriteria(linearIdCondition)

            val criteria = basicCriteria.and(customIndexCriteria1.or(customIndexCriteria2))
            val results = vaultQuerySvc.queryBy<LinearState>(criteria)

            assertThat(results.states).hasSize(2)
        }
    }

    /**
     *  USE CASE demonstrations (outside of mainline Corda)
     *
     *  1) Template / Tutorial CorDapp service using Vault API Custom Query to access attributes of IOU State
     *  2) Template / Tutorial Flow using a JDBC session to execute a custom query
     *  3) Template / Tutorial CorDapp service query extension executing Named Queries via JPA
     *  4) Advanced pagination queries using Spring Data (and/or Hibernate/JPQL)
     */
}


