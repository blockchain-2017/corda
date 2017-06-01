package net.corda.node.services.vault

import io.requery.TransactionIsolation
import io.requery.kotlin.eq
import io.requery.kotlin.select
import io.requery.query.OrderingExpression
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TransactionState
import net.corda.core.crypto.SecureHash
import net.corda.core.node.services.Vault
import net.corda.core.node.services.VaultQueryService
import net.corda.core.node.services.vault.*
import net.corda.core.schemas.requery.Requery
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.storageKryo
import net.corda.core.utilities.loggerFor
import net.corda.node.services.Models
import net.corda.node.services.database.RequeryConfiguration
import net.corda.node.services.vault.schemas.requery.VaultSchema
import java.util.*

class RequeryVaultQueryServiceImpl(dataSourceProperties: Properties) : SingletonSerializeAsToken(), VaultQueryService {

    companion object {
        val log = loggerFor<RequeryVaultQueryServiceImpl>()
    }

    val configuration = RequeryConfiguration(dataSourceProperties, true)
    val session = configuration.sessionForModel(Models.VAULT)

    @Throws(VaultQueryException::class, InvalidQueryCriteriaException::class, InvalidQueryOperatorException::class, UnsupportedQueryException::class)
    override fun <T : ContractState> _queryBy(criteria: QueryCriteria, paging: PageSpecification, sorting: Sort, contractType: Class<out ContractState>): Vault.Page<T> {

        // set defaults: UNCONSUMED, ContractTypes
        val contractTypes = deriveContractTypes(contractType)
        val globalCriteria =
                if (criteria is QueryCriteria.VaultQueryCriteria) {
                    val combinedContractStateTypes = criteria.contractStateTypes?.plus(contractTypes) ?: contractTypes
                    criteria.copy(contractStateTypes = combinedContractStateTypes)
                } else {
                    criteria.and(QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.UNCONSUMED, contractStateTypes = contractTypes))
                }
        val contractTypeMappings = contractTypeMappings
        try {
            val page =
                    session.withTransaction(TransactionIsolation.REPEATABLE_READ) {

                        val query = select(VaultSchema.VaultStates::class)

                        val criteriaParser = RequeryQueryCriteriaParser(contractTypeMappings, query)

                        // derive entity classes to use in select / join
                        val entityClasses = criteriaParser.deriveEntities(globalCriteria)

                        val table1txId = VaultSchema.VaultStates::txId
                        val table1index = VaultSchema.VaultStates::index

                        if (entityClasses.size > 1) {
                            val attributeTxId = RequeryQueryCriteriaParser.findAttribute(entityClasses[1] as Class<Requery.PersistentState>, Requery.PersistentState::txId)
                            val attributeIndex = RequeryQueryCriteriaParser.findAttribute(entityClasses[1] as Class<Requery.PersistentState>, Requery.PersistentState::index)
                            query.join(entityClasses[1].kotlin)
                                    .on(table1txId.eq(attributeTxId)
                                            .and(table1index.eq(attributeIndex)))
                        }

                        // parse criteria
                        criteriaParser.parse(globalCriteria)

                        // Pagination
                        if (paging.pageNumber < 0) throw VaultQueryException("Page specification: invalid page number ${paging.pageNumber} [page numbers start from 0]")
                        if (paging.pageSize < 0 || paging.pageSize > MAX_PAGE_SIZE) throw VaultQueryException("Page specification: invalid page size ${paging.pageSize} [maximum page size is ${MAX_PAGE_SIZE}]")
//                        if (paging.pageNumber == 0)
//                            query.limit((paging.pageNumber + 1) * paging.pageSize)  // cannot specify limit explicitly if using an offset

                        // Sorting
                        criteriaParser.parse(sorting)

                        // Execute
                        val totalStates = query.get().count()   // to enable further pagination
                        if ((totalStates > paging.pageSize) && ((paging.pageNumber * paging.pageSize ) >= totalStates))
                            throw VaultQueryException("Requested more results than exist ($totalStates). Requested page ${paging.pageNumber} with page size of ${paging.pageSize}")

                        // Pagination
                        val boundedIterator = query.get().iterator(paging.pageNumber * paging.pageSize, paging.pageSize)
                        val statesAndRefs: MutableList<StateAndRef<*>> = mutableListOf()
                        val statesMeta: MutableList<Vault.StateMetadata> = mutableListOf()

                        boundedIterator.asSequence()
                                .forEach { it ->
                                    val stateRef = StateRef(SecureHash.parse(it.txId), it.index)
                                    val state = it.contractState.deserialize<TransactionState<T>>(storageKryo())
                                    statesMeta.add(Vault.StateMetadata(stateRef, it.contractStateClassName, it.recordedTime, it.consumedTime, it.stateStatus, it.notaryName, it.notaryKey, it.lockId, it.lockUpdateTime))
                                    statesAndRefs.add(StateAndRef(state, stateRef))
                                }

                        Vault.Page(states = statesAndRefs, statesMetadata = statesMeta, pageable = paging, totalStatesAvailable = totalStates)
                    }

            return page as Vault.Page<T>
        } catch(e: Exception) {
            log.error(e.message)
            throw e.cause ?: e
        }
    }

    override fun <T : ContractState> trackBy(criteria: QueryCriteria, paging: PageSpecification, sorting: Sort): Vault.PageAndUpdates<T> {
        TODO("Under construction")

//        return mutex.locked {
//            Vault.PageAndUpdates(queryBy(criteria),
//                              _updatesPublisher.bufferUntilSubscribed().wrapWithDatabaseTransaction())
//        }
    }

    /**
     * Maintain a list of contract state interfaces to concrete types stored in the vault
     * for usage in generic queries of type queryBy<LinearState> or queryBy<FungibleState<*>>
     */
    val contractTypeMappings: MutableMap<String, MutableList<String>> get() {
        val distinctTypes =
                session.withTransaction(TransactionIsolation.REPEATABLE_READ) {
                    val clazzes = select(VaultSchema.VaultStates::contractStateClassName).distinct()
                    clazzes.get().toList()
                }

        val contractInterfaceToConcreteTypes = mutableMapOf<String, MutableList<String>>()
        distinctTypes.forEach { it ->
            val concreteType = Class.forName(it.get(0)) as Class<ContractState>
            val contractInterfaces = deriveContractInterfaces(concreteType)
            contractInterfaces.map {
                val contractInterface = contractInterfaceToConcreteTypes.getOrPut( it.name, { mutableListOf() })
                contractInterface.add(concreteType.name)
            }
        }
        return contractInterfaceToConcreteTypes
    }

    private fun <T: ContractState> deriveContractInterfaces(clazz: Class<T>): Set<Class<T>> {
        val myInterfaces: MutableSet<Class<T>> = mutableSetOf()
        clazz.interfaces.forEach {
            if (!it.equals(ContractState::class.java)) {
                myInterfaces.add(it as Class<T>)
                myInterfaces.addAll(deriveContractInterfaces(it))
            }
        }
        return myInterfaces
    }
}
