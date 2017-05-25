package net.corda.node.services.database

import net.corda.contracts.testing.consumeCash
import net.corda.contracts.testing.fillWithSomeTestCash
import net.corda.contracts.testing.fillWithSomeTestDeals
import net.corda.contracts.testing.fillWithSomeTestLinearStates
import net.corda.core.contracts.DOLLARS
import net.corda.core.contracts.POUNDS
import net.corda.core.node.services.Vault
import net.corda.core.node.services.VaultService
import net.corda.core.node.services.vault.stateRefArgs
import net.corda.core.schemas.PersistentStateRef
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.DUMMY_NOTARY
import net.corda.node.services.schema.NodeSchemaService
import net.corda.node.services.vault.schemas.jpa.CommonSchemaV1
import net.corda.node.services.vault.schemas.jpa.VaultSchemaV1
import net.corda.node.utilities.configureDatabase
import net.corda.node.utilities.transaction
import net.corda.schemas.CashSchemaV1
import net.corda.schemas.DummyLinearStateSchemaV1
import net.corda.testing.MEGA_CORP_KEY
import net.corda.testing.node.MockServices
import net.corda.testing.node.makeTestDataSourceProperties
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.jetbrains.exposed.sql.Database
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.time.Instant
import java.util.*
import javax.persistence.EntityManager
import javax.persistence.Tuple
import javax.persistence.criteria.CriteriaBuilder

class HibernateConfigurationTest {

    lateinit var services: MockServices
    lateinit var dataSource: Closeable
    lateinit var database: Database

    // Hibernate configuration objects
    lateinit var hibernateConfig: HibernateConfiguration
    lateinit var session: SessionFactory
    lateinit var entityManager: EntityManager
    lateinit var criteriaBuilder: CriteriaBuilder

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

        session = hibernateConfig.sessionFactoryForSchema(VaultSchemaV1)
        entityManager = session.createEntityManager()
        criteriaBuilder = session.criteriaBuilder
    }

    private fun setUpDb() {
        database.transaction {
            services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 10, 10, Random(0L))
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
    fun `count rows and last recorded stateRef`() {

        val countQuery = criteriaBuilder.createQuery(Tuple::class.java)
        val vaultStates = countQuery.from(VaultSchemaV1.VaultStates::class.java)

        countQuery.multiselect(criteriaBuilder.count(countQuery.from(VaultSchemaV1.VaultStates::class.java)),
                vaultStates.get<PersistentStateRef>("stateRef"))
        criteriaBuilder.greatest(vaultStates.get<Instant>("recordedTime"))

        val countResult = entityManager.createQuery(countQuery).singleResult
        val totalStates = countResult[0] as Int // count
        val lastRecordedState = countResult[1] as PersistentStateRef // stateRef
        println(lastRecordedState)

        assertThat(totalStates).isEqualTo(10)
    }

    @Test
    fun `consumed states`() {

        services.consumeCash(50.DOLLARS)

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)
        val vaultStates = criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)
        criteriaQuery.where(criteriaBuilder.equal(
                vaultStates.get<Vault.StateStatus>("stateStatus"), Vault.StateStatus.CONSUMED))

        // execute query
        val queryResults = entityManager.createQuery(criteriaQuery).resultList
        queryResults.map { println(it.contractStateClassName) }

        assertThat(queryResults.size).isEqualTo(5)
    }

    @Test
    fun `select by composite primary key`() {

        val issuedStates = services.fillWithSomeTestLinearStates(2)
        val stateRefs = issuedStates.states.map { it.ref }.toList()
        val stateRefArgs = stateRefArgs(stateRefs)
        services.fillWithSomeTestLinearStates(8)

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)
        val vaultStates = criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)
        val compositeKey = vaultStates.get<PersistentStateRef>("stateRef")
        criteriaBuilder.and(compositeKey.`in`(stateRefArgs))

        // execute query
        val queryResults = entityManager.createQuery(criteriaQuery).resultList
        queryResults.map { println(it.stateRef) }

        assertThat(queryResults).hasSize(2)
        assertThat(queryResults.first().stateRef).isEqualTo(issuedStates.states.first().ref)
        assertThat(queryResults.last().stateRef).isEqualTo(issuedStates.states.last().ref)
    }

    @Test
    fun `distinct contract types`() {

        // add 2 more contract types
        services.fillWithSomeTestLinearStates(10)
        services.fillWithSomeTestDeals(listOf("123", "456", "789"))

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)
        val vaultStates = criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)
        criteriaQuery.select(vaultStates.get("contractStateClassName")).distinct(true)

        // execute query
        val queryResults = entityManager.createQuery(criteriaQuery).resultList
        queryResults.map { println(it.contractStateClassName) }

        Assertions.assertThat(queryResults.size).isEqualTo(3)
    }

    @Test
    fun `with sorting`() {

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)
        val vaultStates = criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)
        criteriaQuery.orderBy(criteriaBuilder.desc(vaultStates.get<Instant>("recordedTime")))

        // execute query
        val queryResults = entityManager.createQuery(criteriaQuery).resultList
        queryResults.map { println(it.recordedTime) }

        Assertions.assertThat(queryResults.size).isEqualTo(10)
    }

    @Test
    fun `with pagination`() {

        // add 100 additional cash entries
        database.transaction {
            services.fillWithSomeTestCash(1000.POUNDS, DUMMY_NOTARY, 100, 100, Random(0L))
        }

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)

        // set pagination
        val query = entityManager.createQuery(criteriaQuery)
        query.firstResult = 10
        query.maxResults = 15

        // execute query
        val queryResults = query.resultList
        queryResults.forEach { println(it) }

        Assertions.assertThat(queryResults.size).isEqualTo(15)
    }

    /**
     *  VaultLinearState is a concrete table, extendible by any Contract extending a LinearState
     */
    @Test
    fun `select and join by composite primary key on LinearStates`() {

        val issuedStates = services.fillWithSomeTestLinearStates(2)
        services.fillWithSomeTestLinearStates(8)

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)
        val vaultStates = criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)
        vaultStates.join<VaultSchemaV1.VaultStates,CommonSchemaV1.LinearState>("stateRef")

        // execute query
        val queryResults = entityManager.createQuery(criteriaQuery).resultList
        queryResults.map { println(it.stateRef) }

        assertThat(queryResults).hasSize(2)
        assertThat(queryResults.first().stateRef).isEqualTo(issuedStates.states.first().ref)
        assertThat(queryResults.last().stateRef).isEqualTo(issuedStates.states.last().ref)
    }

    /**
     *  VaultFungibleState is an abstract table, which should be extended any Contract extending a FungibleAsset
     */
    @Test
    fun `select and join by composite primary key on CashStates`() {

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)
        val vaultStates = criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)
        vaultStates.join<VaultSchemaV1.VaultStates, CashSchemaV1.PersistentCashState>("stateRef")

        // execute query
        val queryResults = entityManager.createQuery(criteriaQuery).resultList
        queryResults.map { println(it.stateRef) }

        assertThat(queryResults).hasSize(2)
    }

    /**
     *  Represents a 3-way join between:
     *      - VaultStates
     *      - VaultLinearStates
     *      - a concrete LinearState implementation (eg. DummyLinearState)
     */
    @Test
    fun `three way join by composite primary between VaultStates, VaultLinearStates and DummyLinearStates`() {

        val issuedStates = services.fillWithSomeTestLinearStates(2)
        services.fillWithSomeTestLinearStates(8)

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)
        val vaultStates = criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)
        vaultStates.join<VaultSchemaV1.VaultStates,CommonSchemaV1.LinearState>("stateRef")
                   .join<CommonSchemaV1.LinearState, DummyLinearStateSchemaV1.PersistentDummyLinearState>("stateRef")

        // execute query
        val queryResults = entityManager.createQuery(criteriaQuery).resultList
        queryResults.map { println(it.stateRef) }

        assertThat(queryResults).hasSize(2)
        assertThat(queryResults.first().stateRef).isEqualTo(issuedStates.states.first().ref)
        assertThat(queryResults.last().stateRef).isEqualTo(issuedStates.states.last().ref)
    }

    /**
     *  Test a OneToOne table mapping
     */
    @Test
    fun `select fungible states by owner party`() {

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(CashSchemaV1.PersistentCashState::class.java)
        val vaultStates = criteriaQuery.from(CashSchemaV1.PersistentCashState::class.java)
        vaultStates.join<CashSchemaV1.PersistentCashState, CommonSchemaV1.Party>("owner")

        // execute query
        val queryResults = entityManager.createQuery(criteriaQuery).resultList
        queryResults.map { println(it.stateRef) }

        assertThat(queryResults).hasSize(2)
    }

    /**
     *  Test a OneToMany table mapping
     */
    @Test
    fun `select fungible states by participants`() {

        // structure query
        val criteriaQuery = criteriaBuilder.createQuery(CashSchemaV1.PersistentCashState::class.java)
        val vaultStates = criteriaQuery.from(CashSchemaV1.PersistentCashState::class.java)
        vaultStates.join<CashSchemaV1.PersistentCashState, CommonSchemaV1.Party>("participants")

        // execute query
        val queryResults = entityManager.createQuery(criteriaQuery).resultList
        queryResults.map { println(it.stateRef) }

        assertThat(queryResults).hasSize(2)
    }

}