package net.corda.node.services.database

import net.corda.contracts.asset.Cash
import net.corda.contracts.testing.consumeCash
import net.corda.contracts.testing.fillWithSomeTestCash
import net.corda.contracts.testing.fillWithSomeTestDeals
import net.corda.contracts.testing.fillWithSomeTestLinearStates
import net.corda.core.contracts.DOLLARS
import net.corda.core.contracts.POUNDS
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.node.services.Vault
import net.corda.core.node.services.VaultService
import net.corda.core.schemas.PersistentStateRef
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.DUMMY_NOTARY
import net.corda.node.services.schema.NodeSchemaService
import net.corda.node.services.vault.schemas.jpa.CommonSchemaV1
import net.corda.node.services.vault.schemas.jpa.VaultSchemaV1
import net.corda.node.utilities.configureDatabase
import net.corda.node.utilities.transaction
import net.corda.schemas.*
import net.corda.testing.MEGA_CORP_KEY
import net.corda.testing.node.MockServices
import net.corda.testing.node.makeTestDataSourceProperties
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.FlushMode
import org.hibernate.SessionFactory
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.time.Instant
import java.util.*
import javax.persistence.EntityManager
import javax.persistence.criteria.CriteriaBuilder

class HibernateConfigurationTest {

    lateinit var services: MockServices
    lateinit var dataSource: Closeable
    lateinit var database: Database

    // Hibernate configuration objects
    lateinit var hibernateConfig: HibernateConfiguration
    lateinit var sessionFactory: SessionFactory
    lateinit var entityManager: EntityManager
    lateinit var criteriaBuilder: CriteriaBuilder

    // test States
    lateinit var cashStates: List<StateAndRef<Cash.State>>

    @Before
    fun setUp() {
        val dataSourceProps = makeTestDataSourceProperties()
        val dataSourceAndDatabase = configureDatabase(dataSourceProps)
        dataSource = dataSourceAndDatabase.first
        database = dataSourceAndDatabase.second
        database.transaction {

            hibernateConfig = HibernateConfiguration(NodeSchemaService())

            services = object : MockServices(MEGA_CORP_KEY) {

                override val vaultService: VaultService = makeVaultService(dataSourceProps, hibernateConfig)

                override fun recordTransactions(txs: Iterable<SignedTransaction>) {
                    for (stx in txs) {
                        storageService.validatedTransactions.addTransaction(stx)
                    }
                    // Refactored to use notifyAll() as we have no other unit test for that method with multiple transactions.
                    vaultService.notifyAll(txs.map { it.tx })
                }
            }
        }
        setUpDb()

        sessionFactory = hibernateConfig.sessionFactoryForSchema(VaultSchemaV1)
        entityManager = sessionFactory.createEntityManager()
        criteriaBuilder = sessionFactory.criteriaBuilder
    }

    @After
    fun cleanUp() {
        dataSource.close()
    }

    private fun setUpDb() {
        database.transaction {
            cashStates = services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 10, 10, Random(0L)).states.toList()
        }
    }

    @Test
    fun `count rows`() {
        // structure query
        val countQuery = criteriaBuilder.createQuery(Long::class.java)
        countQuery.select(criteriaBuilder.count(countQuery.from(VaultSchemaV1.VaultStates::class.java)))

        // execute query
        val countResult = entityManager.createQuery(countQuery).singleResult

        assertThat(countResult).isEqualTo(10)
    }

    @Test
    fun `consumed states`() {

        database.transaction {
            services.consumeCash(50.DOLLARS)
        }

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)
        val vaultStates = criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)
        criteriaQuery.where(criteriaBuilder.equal(
                vaultStates.get<Vault.StateStatus>("stateStatus"), Vault.StateStatus.CONSUMED))

        // execute query
        val queryResults = entityManager.createQuery(criteriaQuery).resultList
        queryResults.map { println("${it.stateRef}, recorded: ${it.recordedTime}, consumed: ${it.consumedTime}") }

        assertThat(queryResults.size).isEqualTo(6)
    }

    @Test
    fun `select by composite primary key`() {

        val issuedStates =
            database.transaction {
                services.fillWithSomeTestLinearStates(8)
                services.fillWithSomeTestLinearStates(2)
            }
        val persistentStateRefs = issuedStates.states.map { PersistentStateRef(it.ref) }.toList()

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)
        val vaultStates = criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)
        val compositeKey = vaultStates.get<PersistentStateRef>("stateRef")
        criteriaQuery.where(criteriaBuilder.and(compositeKey.`in`(persistentStateRefs)))

        // execute query
        val queryResults = entityManager.createQuery(criteriaQuery).resultList
        queryResults.map { println(it.stateRef) }

        assertThat(queryResults).hasSize(2)
        assertThat(queryResults.first().stateRef?.txId).isEqualTo(issuedStates.states.first().ref.txhash.toString())
        assertThat(queryResults.first().stateRef?.index).isEqualTo(issuedStates.states.first().ref.index)
        assertThat(queryResults.last().stateRef?.txId).isEqualTo(issuedStates.states.last().ref.txhash.toString())
        assertThat(queryResults.last().stateRef?.index).isEqualTo(issuedStates.states.last().ref.index)
    }

    @Test
    fun `distinct contract types`() {

        database.transaction {
            // add 2 more contract types
            services.fillWithSomeTestLinearStates(10)
            services.fillWithSomeTestDeals(listOf("123", "456", "789"))
        }

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(String::class.java)
        val vaultStates = criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)
        criteriaQuery.select(vaultStates.get("contractStateClassName")).distinct(true)

        // execute query
        val queryResults = entityManager.createQuery(criteriaQuery).resultList
        queryResults.map { println(it) }

        Assertions.assertThat(queryResults.size).isEqualTo(3)
    }

    @Test
    fun `with sorting`() {

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)
        val vaultStates = criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)

        // order by DESC
        criteriaQuery.orderBy(criteriaBuilder.desc(vaultStates.get<Instant>("recordedTime")))
        val queryResults = entityManager.createQuery(criteriaQuery).resultList
        queryResults.map { println(it.recordedTime) }

        // order by ASC
        criteriaQuery.orderBy(criteriaBuilder.asc(vaultStates.get<Instant>("recordedTime")))
        val queryResultsAsc = entityManager.createQuery(criteriaQuery).resultList
        queryResultsAsc.map { println(it.recordedTime) }
    }

    @Test
    fun `with pagination`() {

        // add 100 additional cash entries
        database.transaction {
            services.fillWithSomeTestCash(1000.POUNDS, DUMMY_NOTARY, 100, 100, Random(0L))
        }

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)
        criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)

        // set pagination
        val query = entityManager.createQuery(criteriaQuery)
        query.firstResult = 10
        query.maxResults = 15

        // execute query
        val queryResults = query.resultList
        queryResults.forEach { println(it) }

        Assertions.assertThat(queryResults.size).isEqualTo(15)

        // try towards end
        query.firstResult = 100
        query.maxResults = 15

        val lastQueryResults = query.resultList

        Assertions.assertThat(lastQueryResults.size).isEqualTo(10)

    }

    /**
     *  VaultLinearState is a concrete table, extendible by any Contract extending a LinearState
     */
    @Test
    fun `select by composite primary key on LinearStates`() {

        database.transaction {
            services.fillWithSomeTestLinearStates(10)
        }

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)

        val vaultStates = criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)
        val vaultLinearStates = criteriaQuery.from(VaultSchemaV1.VaultLinearStates::class.java)

        criteriaQuery.select(vaultStates)
        criteriaQuery.where(criteriaBuilder.equal(vaultStates.get<PersistentStateRef>("stateRef"), vaultLinearStates.get<PersistentStateRef>("stateRef")))

        // execute query
        val queryResults = entityManager.createQuery(criteriaQuery).resultList
        queryResults.forEach { println("${it.stateRef} of type ${it.contractStateClassName}") }

        assertThat(queryResults).hasSize(10)
    }

    /**
     *  VaultFungibleState is an abstract entity, which should be extended any Contract extending a FungibleAsset
     */

    /**
     *  CashSchemaV1 = original Cash schema (extending PersistentState)
     */
    @Test
    fun `count CashStates`() {

        val sessionFactory = hibernateConfig.sessionFactoryForSchema(CashSchemaV1)
        val entityManager = sessionFactory.createEntityManager()
        val criteriaBuilder = entityManager.criteriaBuilder

        // structure query
        val countQuery = criteriaBuilder.createQuery(Long::class.java)
        countQuery.select(criteriaBuilder.count(countQuery.from(CashSchemaV1.PersistentCashState::class.java)))

        // execute query
        val countResult = entityManager.createQuery(countQuery).singleResult

        assertThat(countResult).isEqualTo(10)
    }

    @Test
    fun `select by composite primary key on CashStates`() {

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)
        val vaultStates = criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)
        vaultStates.join<VaultSchemaV1.VaultStates, CashSchemaV1.PersistentCashState>("stateRef")

        // execute query
        val queryResults = entityManager.createQuery(criteriaQuery).resultList
        queryResults.map { println(it.stateRef) }

        assertThat(queryResults).hasSize(10)
    }

    @Test
    fun `select and join by composite primary key on CashStates`() {

        database.transaction {
            services.fillWithSomeTestLinearStates(5)

            val sessionFactory = hibernateConfig.sessionFactoryForSchemas(VaultSchemaV1, CashSchemaV1)
            val criteriaBuilder = sessionFactory.criteriaBuilder
            val entityManager = sessionFactory.createEntityManager()

            // structure query
            val criteriaQuery = criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)
            val vaultStates = criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)
            val vaultCashStates = criteriaQuery.from(CashSchemaV1.PersistentCashState::class.java)

            criteriaQuery.select(vaultStates)
            criteriaQuery.where(criteriaBuilder.equal(vaultStates.get<PersistentStateRef>("stateRef"), vaultCashStates.get<PersistentStateRef>("stateRef")))

            // execute query
            val queryResults = entityManager.createQuery(criteriaQuery).resultList
            queryResults.forEach { println("${it.stateRef} of type ${it.contractStateClassName}") }

            assertThat(queryResults).hasSize(10)
        }
    }

    /**
     *  CashSchemaV2 = optimised Cash schema (extending FungibleState)
     */
    @Test
    fun `count CashStates in V2`() {

        val sessionFactory = hibernateConfig.sessionFactoryForSchemas(CommonSchemaV1, CashSchemaV2)
        val entityManager = sessionFactory.createEntityManager()
        val criteriaBuilder = entityManager.criteriaBuilder

        // structure query
        val countQuery = criteriaBuilder.createQuery(Long::class.java)
        countQuery.select(criteriaBuilder.count(countQuery.from(CashSchemaV2.PersistentCashState::class.java)))

        // execute query
        val countResult = entityManager.createQuery(countQuery).singleResult

        assertThat(countResult).isEqualTo(10)
    }

    @Test
    fun `select by composite primary key on CashStates in V2`() {

        database.transaction {
            services.fillWithSomeTestLinearStates(5)
        }

        val sessionFactory = hibernateConfig.sessionFactoryForSchemas(VaultSchemaV1, CashSchemaV2)
        val criteriaBuilder = sessionFactory.criteriaBuilder
        val entityManager = sessionFactory.createEntityManager()

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)
        val vaultStates = criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)
        val vaultCashStates = criteriaQuery.from(CashSchemaV2.PersistentCashState::class.java)

        criteriaQuery.select(vaultStates)
        criteriaQuery.where(criteriaBuilder.equal(vaultStates.get<PersistentStateRef>("stateRef"), vaultCashStates.get<PersistentStateRef>("stateRef")))

        // execute query
        val queryResults = entityManager.createQuery(criteriaQuery).resultList
        queryResults.forEach { println("${it.stateRef} of type ${it.contractStateClassName}") }

        assertThat(queryResults).hasSize(10)
    }

    /**
     *  Represents a 3-way join between:
     *      - VaultStates
     *      - VaultLinearStates
     *      - a concrete LinearState implementation (eg. DummyLinearState)
     */

    /**
     *  DummyLinearStateV1 = original DummyLinearState schema (extending PersistentState)
     */
    @Test
    fun `select by composite primary between VaultStates, VaultLinearStates and DummyLinearStates`() {

        database.transaction {
            services.fillWithSomeTestLinearStates(8)
            services.fillWithSomeTestDeals(listOf("123", "456", "789"))
            services.fillWithSomeTestLinearStates(2)
        }

        val sessionFactory = hibernateConfig.sessionFactoryForSchemas(VaultSchemaV1, DummyLinearStateSchemaV1)
        val criteriaBuilder = sessionFactory.criteriaBuilder
        val entityManager = sessionFactory.createEntityManager()

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)
        val vaultStates = criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)
        val vaultLinearStates = criteriaQuery.from(VaultSchemaV1.VaultLinearStates::class.java)
        val dummyLinearStates = criteriaQuery.from(DummyLinearStateSchemaV1.PersistentDummyLinearState::class.java)

        criteriaQuery.select(vaultStates)
        val joinPredicate1 = criteriaBuilder.equal(vaultStates.get<PersistentStateRef>("stateRef"), vaultLinearStates.get<PersistentStateRef>("stateRef"))
        val joinPredicate2 = criteriaBuilder.and(criteriaBuilder.equal(vaultStates.get<PersistentStateRef>("stateRef"), dummyLinearStates.get<PersistentStateRef>("stateRef")))
        criteriaQuery.where(joinPredicate1, joinPredicate2)

        // execute query
        val queryResults = entityManager.createQuery(criteriaQuery).resultList
        queryResults.forEach { println("${it.stateRef} of type ${it.contractStateClassName}") }

        assertThat(queryResults).hasSize(10)
    }

    /**
     *  DummyLinearSchemaV2 = optimised DummyLinear schema (extending LinearState)
     */

    @Test
    fun `three way join by composite primary between VaultStates, VaultLinearStates and DummyLinearStates`() {

        database.transaction {
            services.fillWithSomeTestLinearStates(8)
            services.fillWithSomeTestDeals(listOf("123", "456", "789"))
            services.fillWithSomeTestLinearStates(2)
        }

        val sessionFactory = hibernateConfig.sessionFactoryForSchemas(VaultSchemaV1, DummyLinearStateSchemaV2)
        val criteriaBuilder = sessionFactory.criteriaBuilder
        val entityManager = sessionFactory.createEntityManager()

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)
        val vaultStates = criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)
        val vaultLinearStates = criteriaQuery.from(VaultSchemaV1.VaultLinearStates::class.java)
        val dummyLinearStates = criteriaQuery.from(DummyLinearStateSchemaV2.PersistentDummyLinearState::class.java)

        criteriaQuery.select(vaultStates)
        val joinPredicate1 = criteriaBuilder.equal(vaultStates.get<PersistentStateRef>("stateRef"), vaultLinearStates.get<PersistentStateRef>("stateRef"))
        val joinPredicate2 = criteriaBuilder.and(criteriaBuilder.equal(vaultStates.get<PersistentStateRef>("stateRef"), dummyLinearStates.get<PersistentStateRef>("stateRef")))
        criteriaQuery.where(joinPredicate1, joinPredicate2)

        // execute query
        val queryResults = entityManager.createQuery(criteriaQuery).resultList
        queryResults.forEach { println("${it.stateRef} of type ${it.contractStateClassName}") }

        assertThat(queryResults).hasSize(10)
    }

    /**
     *  Test a OneToOne table mapping
     */
    @Test
    fun `select fungible states by owner party`() {

        val sessionFactory = hibernateConfig.sessionFactoryForSchemas(CommonSchemaV1, CashSchemaV3)
        val criteriaBuilder = sessionFactory.criteriaBuilder
        val entityManager = sessionFactory.createEntityManager()

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(CashSchemaV3.PersistentCashState::class.java)
        criteriaQuery.from(CashSchemaV3.PersistentCashState::class.java)

        // execute query
        val queryResults = entityManager.createQuery(criteriaQuery).resultList
        queryResults.forEach { println("${it.stateRef} with owner name: ${it.owner.name} and owner key: ${it.owner.key}")}

        assertThat(queryResults).hasSize(10)
    }

    /**
     *  Test a OneToMany table mapping
     */
    @Test
    fun `select fungible states by participants`() {

        val sessionFactory = hibernateConfig.sessionFactoryForSchemas(CommonSchemaV1, CashSchemaV3)
        val criteriaBuilder = sessionFactory.criteriaBuilder
        val entityManager = sessionFactory.createEntityManager()

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(CashSchemaV3.PersistentCashState::class.java)
        criteriaQuery.from(CashSchemaV3.PersistentCashState::class.java)

        // execute query
        val queryResults = entityManager.createQuery(criteriaQuery).resultList
        queryResults.forEach { println("${it.stateRef} with participants ${it.pennies}") }

        assertThat(queryResults).hasSize(10)
    }
}