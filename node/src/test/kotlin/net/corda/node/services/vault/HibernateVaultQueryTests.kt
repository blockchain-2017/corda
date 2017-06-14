package net.corda.node.services.vault

import net.corda.core.contracts.ContractState
import net.corda.core.node.services.*
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.Sort
import net.corda.core.transactions.SignedTransaction
import net.corda.node.services.database.HibernateConfiguration
import net.corda.node.services.schema.NodeSchemaService
import net.corda.node.services.vault.schemas.jpa.VaultSchemaV1
import net.corda.node.utilities.configureDatabase
import net.corda.node.utilities.transaction
import net.corda.schemas.CommercialPaperSchemaV1
import net.corda.schemas.CommercialPaperSchemaV2
import net.corda.testing.MEGA_CORP_KEY
import net.corda.testing.node.MockServices
import net.corda.testing.node.makeTestDataSourceProperties
import org.junit.After
import org.junit.Before
import org.junit.Test

class HibernateVaultQueryTests : VaultQueryTests() {
    @Before
    fun setUp() {
        val dataSourceProps = makeTestDataSourceProperties()
        val dataSourceAndDatabase = configureDatabase(dataSourceProps)
        dataSource = dataSourceAndDatabase.first
        database = dataSourceAndDatabase.second
        database.transaction {
            val customSchemas = setOf(CommercialPaperSchemaV1, CommercialPaperSchemaV2)
            val hibernateConfig = HibernateConfiguration(NodeSchemaService(customSchemas))
            services = object : MockServices(MEGA_CORP_KEY) {
                override val vaultService: VaultService = makeVaultService(dataSourceProps, hibernateConfig)

                override fun recordTransactions(txs: Iterable<SignedTransaction>) {
                    for (stx in txs) {
                        storageService.validatedTransactions.addTransaction(stx)
                    }
                    // Refactored to use notifyAll() as we have no other unit test for that method with multiple transactions.
                    vaultService.notifyAll(txs.map { it.tx })
                }
                override val vaultQueryService : VaultQueryService = HibernateVaultQueryImpl(hibernateConfig, vaultService.updatesPublisher)
            }
        }
    }

    @After
    fun tearDown() {
        dataSource.close()
    }

    /* Nulls handling not supported in JPA implementation */

    @Test(expected = VaultQueryException::class)
    fun `sorting with null specification LAST`() {
        database.transaction {
            val sortBy = Sort.SortColumn(VaultSchemaV1.VaultStates::class.java, VaultSchemaV1.VaultStates::contractStateClassName.name,
                    nullHandling = Sort.NullHandling.NULLS_LAST)
            vaultQuerySvc.queryBy<ContractState>(QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.ALL), sorting = Sort(setOf(sortBy)))
        }
    }

    @Test(expected = VaultQueryException::class)
    fun `sorting with null specification FIRST`() {
        database.transaction {
            val sortBy = Sort.SortColumn(VaultSchemaV1.VaultStates::class.java, VaultSchemaV1.VaultStates::contractStateClassName.name,
                    nullHandling = Sort.NullHandling.NULLS_FIRST)
            vaultQuerySvc.queryBy<ContractState>(QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.ALL), sorting = Sort(setOf(sortBy)))
        }
    }

    @Test
    fun `sorting with default null specification`() {
        database.transaction {
            val sortBy = Sort.SortColumn(VaultSchemaV1.VaultStates::class.java, VaultSchemaV1.VaultStates::contractStateClassName.name)
            vaultQuerySvc.queryBy<ContractState>(QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.ALL), sorting = Sort(setOf(sortBy)))
        }
    }
}


