package net.corda.node.services.vault

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.*
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.PersistentStateRef
import net.corda.core.serialization.toHexString
import net.corda.node.services.vault.schemas.jpa.VaultSchemaV1
import org.bouncycastle.asn1.x500.X500Name
import java.time.Instant
import java.util.*
import javax.persistence.criteria.*
import kotlin.jvm.internal.MutablePropertyReference1
import kotlin.reflect.KClass


class HibernateQueryCriteriaParser(val contractTypeMappings: Map<String, List<String>>,
                                   val criteriaBuilder: CriteriaBuilder,
                                   val criteriaQuery: CriteriaQuery<VaultSchemaV1.VaultStates>,
                                   val vaultStates: Root<VaultSchemaV1.VaultStates>,
                                   val contractType: Class<out ContractState>,
                                   private var predicates : MutableList<Predicate> = mutableListOf(),
                                   private var fromEntities: MutableMap<Class<out PersistentState>, Root<*>> = mutableMapOf()) : IQueryCriteriaParser {

    override fun parseCriteria(criteria: QueryCriteria.VaultQueryCriteria) {

        // state status
        if (criteria.status == Vault.StateStatus.ALL)
            predicates.add(vaultStates.get<Vault.StateStatus>("stateStatus").`in`(setOf(Vault.StateStatus.UNCONSUMED, Vault.StateStatus.CONSUMED)))
        else
            predicates.add(criteriaBuilder.equal(vaultStates.get<Vault.StateStatus>("stateStatus"), criteria.status))

        // contract State Types
        val combinedContractTypeTypes = criteria.contractStateTypes?.plus(contractType) ?: setOf(contractType)
        combinedContractTypeTypes?.filter { it.name != ContractState::class.java.name }?.let {
            val interfaces = it.flatMap { contractTypeMappings[it.name] ?: emptyList() }
            val concrete = it.filter { !it.isInterface }.map { it.name }
            val all = interfaces.plus(concrete)
            if (all.isNotEmpty())
                predicates.add(criteriaBuilder.and(vaultStates.get<String>("contractStateClassName").`in`(all)))
        }

        // soft locking
        if (!criteria.includeSoftlockedStates)
            predicates.add(criteriaBuilder.and(vaultStates.get<String>("lockId").isNull))

        // notary names
        criteria.notaryName?.let {
            val notaryNames = (criteria.notaryName as List<X500Name>).map { it.toString() }
            predicates.add(criteriaBuilder.and(vaultStates.get<String>("notaryName").`in`(notaryNames)))
        }

        // state references
        criteria.stateRefs?.let {
            val persistentStateRefs = (criteria.stateRefs as List<StateRef>).map { PersistentStateRef(it.txhash.bytes.toHexString(), it.index) }
            val compositeKey = vaultStates.get<PersistentStateRef>("stateRef")
            predicates.add(criteriaBuilder.and(compositeKey.`in`(persistentStateRefs)))
        }

        // time constraints (recorded, consumed)
        criteria.timeCondition?.let {
            val timeCondition = criteria.timeCondition
            val timeInstantType = timeCondition!!.leftOperand
            val timeOperator = timeCondition?.operator
            val timeValue = timeCondition?.rightOperand
            predicates.add(
                when (timeInstantType) {
                    QueryCriteria.TimeInstantType.CONSUMED ->
                        criteriaBuilder.and(parseOperator(timeOperator, vaultStates.get<Instant>("consumedTime"), timeValue))
                    QueryCriteria.TimeInstantType.RECORDED ->
                        criteriaBuilder.and(parseOperator(timeOperator, vaultStates.get<Instant>("recordedTime"), timeValue))
                })
        }

        // participants (are associated with all ContractState types but not stored in the Vault States table - should they?)
        criteria.participantIdentities?.let {
            throw UnsupportedQueryException("Unable to query on contract state participants until identity schemas defined")
        }
    }

    private fun parseOperator(operator: Operator, attribute: Path<Instant>?, value: Array<Instant>): Predicate? {
        val predicate =
                when (operator) {
                    Operator.EQUAL -> criteriaBuilder.equal(attribute, value[0])
                    Operator.NOT_EQUAL -> criteriaBuilder.notEqual(attribute, value[0])
                    Operator.GREATER_THAN -> criteriaBuilder.greaterThan(attribute, value[0])
                    Operator.GREATER_THAN_OR_EQUAL -> criteriaBuilder.greaterThanOrEqualTo(attribute, value[0])
                    Operator.LESS_THAN -> criteriaBuilder.lessThan(attribute, value[0])
                    Operator.LESS_THAN_OR_EQUAL -> criteriaBuilder.lessThanOrEqualTo(attribute, value[0])
                    Operator.BETWEEN -> criteriaBuilder.between(attribute, value[0],value[1])
                    else -> throw InvalidQueryOperatorException(operator)
                }
        return predicate
    }

    override fun parseCriteria(criteria: QueryCriteria.FungibleAssetQueryCriteria) {

        val vaultFungibleStates = criteriaQuery.from(VaultSchemaV1.VaultFungibleStates::class.java)
        fromEntities.putIfAbsent(VaultSchemaV1.VaultFungibleStates::class.java, vaultFungibleStates)
        criteriaQuery.select(vaultStates)
        val joinPredicate = criteriaBuilder.and(criteriaBuilder.equal(vaultStates.get<PersistentStateRef>("stateRef"), vaultFungibleStates.get<PersistentStateRef>("stateRef")))
        predicates.add(joinPredicate)

        // quantity
        criteria.quantity?.let {
            val operator = it.operator
            val value = it.rightOperand
            predicates.add(criteriaBuilder.and(parseGenericOperator(operator, vaultFungibleStates.get<Long>("quantity"), value)))
        }

        criteriaQuery.select(vaultStates)
    }

    override fun parseCriteria(criteria: QueryCriteria.LinearStateQueryCriteria) {

        val vaultLinearStates = criteriaQuery.from(VaultSchemaV1.VaultLinearStates::class.java)
        fromEntities.putIfAbsent(VaultSchemaV1.VaultLinearStates::class.java, vaultLinearStates)
        criteriaQuery.select(vaultStates)
        val joinPredicate = criteriaBuilder.and(criteriaBuilder.equal(vaultStates.get<PersistentStateRef>("stateRef"), vaultLinearStates.get<PersistentStateRef>("stateRef")))
        predicates.add(joinPredicate)

        // linear ids
        criteria.linearId?.let {
            val uniqueIdentifiers = criteria.linearId as List<UniqueIdentifier>
            val externalIds = uniqueIdentifiers.mapNotNull { it.externalId }
            if (externalIds.size > 0) {
                predicates.add(criteriaBuilder.and(vaultLinearStates.get<String>("externalId").`in`(externalIds)))
            }
            predicates.add(criteriaBuilder.and(vaultLinearStates.get<UUID>("uuid").`in`(uniqueIdentifiers.map { it.id })))
        }

        // deal refs
        criteria.dealRef?.let {
            val dealRefs = criteria.dealRef as List<String>
            predicates.add(criteriaBuilder.and(vaultLinearStates.get<String>("dealReference").`in`(dealRefs)))
        }

    }

    override fun <L : Any, R : Comparable<R>> parseCriteria(criteria: QueryCriteria.VaultCustomQueryCriteria<L, R>) {

        val attribute = criteria.indexExpression.leftOperand
        val entity = (attribute as MutablePropertyReference1).owner
        val entityClass = (attribute.owner as KClass<*>).java
        val entityRoot = criteriaQuery.from(entityClass)

        val attributeName = attribute.name
        val operator = criteria.indexExpression.operator
        val value = criteria.indexExpression.rightOperand

        predicates.add(
            criteriaBuilder.and(parseGenericOperator(operator, entityRoot.get<R>(attributeName), value))
        )

        criteriaQuery.select(vaultStates)
        val joinPredicate = criteriaBuilder.and(criteriaBuilder.equal(vaultStates.get<PersistentStateRef>("stateRef"), entityRoot.get<R>("stateRef")))
        predicates.add(joinPredicate)
    }

    private fun <T : Comparable<T>> parseGenericOperator(operator: Operator, attribute: Path<out T>?, value: T): Predicate? {

        val predicate =
                when (operator) {
                    Operator.EQUAL -> criteriaBuilder.equal(attribute, value)
                    Operator.NOT_EQUAL -> criteriaBuilder.notEqual(attribute, value)
                    Operator.GREATER_THAN -> criteriaBuilder.greaterThan(attribute, value)
                    Operator.GREATER_THAN_OR_EQUAL -> criteriaBuilder.greaterThanOrEqualTo(attribute, value)
                    Operator.LESS_THAN -> criteriaBuilder.lessThan(attribute, value)
                    Operator.LESS_THAN_OR_EQUAL -> criteriaBuilder.lessThanOrEqualTo(attribute, value)
                    Operator.BETWEEN -> {
                        val multiValue = value as Collection<T>
                        criteriaBuilder.between(attribute, multiValue.first(), multiValue.last())
                    }
                    else -> throw InvalidQueryOperatorException(operator)
                }
        return predicate
    }

    override fun parse(criteria: QueryCriteria) {
        criteria.visit(this)
        criteriaQuery.where(*predicates.toTypedArray())
    }

    override fun parse(sorting: Sort) {
        sorting.columns.map { (entityStateClass, entityStateColumnName, direction) ->
            val sortEntityRoot =
                    fromEntities.getOrElse(entityStateClass) { criteriaQuery.from(entityStateClass) }
            when (direction) {
                Sort.Direction.ASC -> {
                    criteriaQuery.orderBy(criteriaBuilder.asc(sortEntityRoot.get<String>(entityStateColumnName)))
                }
                Sort.Direction.DESC ->
                    criteriaQuery.orderBy(criteriaBuilder.desc(sortEntityRoot.get<String>(entityStateColumnName)))
            }
        }
    }
}