package net.corda.node.services.vault

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TransactionState
import net.corda.core.crypto.SecureHash
import net.corda.core.node.services.Vault
import net.corda.core.node.services.VaultQueryService
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.Sort
import net.corda.core.schemas.PersistentStateRef
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.storageKryo
import net.corda.core.utilities.loggerFor
import net.corda.node.services.database.HibernateConfiguration
import net.corda.node.services.schema.NodeSchemaService
import java.lang.Exception
import sun.misc.MessageUtils.where
import java.time.Instant
import javax.persistence.Tuple
import javax.persistence.criteria.CriteriaBuilder



class HibernateVaultQueryImpl : SingletonSerializeAsToken(), VaultQueryService {

    companion object {
        val log = loggerFor<HibernateVaultQueryImpl>()
    }

    private val configuration = HibernateConfiguration(NodeSchemaService())
    private val session = configuration.sessionFactoryForSchema(VaultSchemaV1)

    @Throws(VaultQueryException::class)
    override fun <T : ContractState> _queryBy(criteria: QueryCriteria, paging: PageSpecification, sorting: Sort, contractType: Class<out ContractState>): Vault.Page<T> {

        val criteriaParser = HibernateQueryCriteriaParser(session.criteriaBuilder, contractTypeMappings)

        // parse criteria
        criteriaParser.parse(criteria)

        try {
            val cb = session.criteriaBuilder

            val criteriaQuery = cb.createQuery(VaultSchemaV1.VaultStates::class.java)
            val vaultStates = criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)

            criteriaQuery.where(cb.equal(vaultStates.get<Vault.StateStatus>("stateStatus"), Vault.StateStatus.UNCONSUMED))
            criteriaQuery.orderBy()

            val query = session.createEntityManager().createQuery(criteriaQuery)

            // pagination
            query.firstResult = paging.pageNumber * paging.pageSize
            query.maxResults = paging.pageSize

            val countQuery = cb.createQuery(Tuple::class.java)
            countQuery.multiselect(cb.count(countQuery.from(VaultSchemaV1.VaultStates::class.java)),
                                   vaultStates.get<PersistentStateRef>("stateRef"))
            cb.greatest(vaultStates.get<Instant>("recordedTime"))
            val countResult = session.createEntityManager().createQuery(countQuery).singleResult
            val totalStates = countResult[0] as Int // count
            val lastRecordedState = countResult[1] as PersistentStateRef // stateRef

            // sorting
            sorting.columns.map {
                when(it.direction) {
                    Sort.Direction.ASC ->
                        criteriaQuery.orderBy(cb.asc(vaultStates.get<String>(it.columnName)))
                    Sort.Direction.DESC ->
                        criteriaQuery.orderBy(cb.desc(vaultStates.get<String>(it.columnName)))
                }
            }

            // execution
            val results = query.resultList
            val statesAndRefs: MutableList<StateAndRef<*>> = mutableListOf()
            val statesMeta: MutableList<Vault.StateMetadata> = mutableListOf()

            results.asSequence()
                    .forEach { it ->
                        val stateRef = StateRef(SecureHash.parse(it.stateRef!!.txId!!), it.stateRef!!.index!!)
                        val state = it.contractState.deserialize<TransactionState<T>>(storageKryo())
                        statesMeta.add(Vault.StateMetadata(stateRef, it.contractStateClassName, it.recordedTime, it.consumedTime, it.stateStatus, it.notaryName, it.notaryKey, it.lockId, it.lockUpdateTime))
                        statesAndRefs.add(StateAndRef(state, stateRef))
                    }

            return Vault.Page(states = statesAndRefs, statesMetadata = statesMeta, pageable = paging, totalStatesAvailable = totalStates) as Vault.Page<T>

        } catch (e: Exception) {
            log.error(e.message)
            throw e.cause ?: e
        }
    }

    override fun <T : ContractState> trackBy(criteria: QueryCriteria, paging: PageSpecification, sorting: Sort): Vault.PageAndUpdates<T> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    /**
     * Maintain a list of contract state interfaces to concrete types stored in the vault
     * for usage in generic queries of type queryBy<LinearState> or queryBy<FungibleState<*>>
     */
    val contractTypeMappings: MutableMap<String, MutableList<String>> get() {

        val criteria = session.criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)
        val vaultStates = criteria.from(VaultSchemaV1.VaultStates::class.java)
        criteria.select(vaultStates.get("contractStateClassName")).distinct(true)
        val query = session.createEntityManager().createQuery(criteria)
        val results = query.resultList
        val distinctTypes = results.map { it.contractStateClassName }

        val contractInterfaceToConcreteTypes = mutableMapOf<String, MutableList<String>>()
        distinctTypes.forEach { it ->
            val concreteType = Class.forName(it) as Class<ContractState>
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