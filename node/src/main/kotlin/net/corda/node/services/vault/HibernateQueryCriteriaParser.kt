package net.corda.node.services.vault

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.toBase58String
import net.corda.core.identity.AbstractParty
import net.corda.core.node.services.Vault
import net.corda.core.node.services.VaultQueryException
import net.corda.core.node.services.vault.*
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.PersistentStateRef
import net.corda.core.serialization.OpaqueBytes
import net.corda.core.serialization.toHexString
import net.corda.core.utilities.loggerFor
import net.corda.core.utilities.trace
import net.corda.node.services.vault.schemas.jpa.CommonSchemaV1
import net.corda.node.services.vault.schemas.jpa.VaultSchemaV1
import org.bouncycastle.asn1.x500.X500Name
import java.lang.reflect.Field
import java.security.PublicKey
import java.time.Instant
import java.util.*
import javax.persistence.criteria.*
import kotlin.jvm.internal.MutablePropertyReference1
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1


class HibernateQueryCriteriaParser(val contractType: Class<out ContractState>,
                                   val contractTypeMappings: Map<String, List<String>>,
                                   val criteriaBuilder: CriteriaBuilder,
                                   val criteriaQuery: CriteriaQuery<VaultSchemaV1.VaultStates>,
                                   val vaultStates: Root<VaultSchemaV1.VaultStates>) : IQueryCriteriaParser {
    private companion object {
        val log = loggerFor<HibernateQueryCriteriaParser>()
    }

    // incrementally build list of join predicates
    private var joinPredicates = mutableListOf<Predicate>()
    // incrementally build list of root entities (for later use in Sort parsing)
    private var rootEntities = mutableMapOf<Class<out PersistentState>, Root<*>>()

    override fun parseCriteria(criteria: QueryCriteria.VaultQueryCriteria) : Collection<Predicate> {

        log.trace { "Parsing VaultQueryCriteria: $criteria" }
        var predicateSet = mutableSetOf<Predicate>()

        rootEntities.putIfAbsent(VaultSchemaV1.VaultStates::class.java, vaultStates)
        criteriaQuery.select(vaultStates)

        // state status
        if (criteria.status == Vault.StateStatus.ALL)
            predicateSet.add(vaultStates.get<Vault.StateStatus>("stateStatus").`in`(setOf(Vault.StateStatus.UNCONSUMED, Vault.StateStatus.CONSUMED)))
        else
            predicateSet.add(criteriaBuilder.equal(vaultStates.get<Vault.StateStatus>("stateStatus"), criteria.status))

        // contract State Types
        val combinedContractTypeTypes = criteria.contractStateTypes?.plus(contractType) ?: setOf(contractType)
        combinedContractTypeTypes.filter { it.name != ContractState::class.java.name }.let {
            val interfaces = it.flatMap { contractTypeMappings[it.name] ?: emptyList() }
            val concrete = it.filter { !it.isInterface }.map { it.name }
            val all = interfaces.plus(concrete)
            if (all.isNotEmpty())
                predicateSet.add(criteriaBuilder.and(vaultStates.get<String>("contractStateClassName").`in`(all)))
        }

        // soft locking
        if (!criteria.includeSoftlockedStates)
            predicateSet.add(criteriaBuilder.and(vaultStates.get<String>("lockId").isNull))

        // notary names
        criteria.notaryName?.let {
            val notaryNames = (criteria.notaryName as List<X500Name>).map { it.toString() }
            predicateSet.add(criteriaBuilder.and(vaultStates.get<String>("notaryName").`in`(notaryNames)))
        }

        // state references
        criteria.stateRefs?.let {
            val persistentStateRefs = (criteria.stateRefs as List<StateRef>).map { PersistentStateRef(it.txhash.bytes.toHexString(), it.index) }
            val compositeKey = vaultStates.get<PersistentStateRef>("stateRef")
            predicateSet.add(criteriaBuilder.and(compositeKey.`in`(persistentStateRefs)))
        }

        // time constraints (recorded, consumed)
        criteria.timeCondition?.let {
            val timeCondition = criteria.timeCondition
            val timeInstantType = timeCondition!!.leftOperand
            val timeOperator = timeCondition.operator
            val timeValue = timeCondition.rightOperand
            predicateSet.add(
                when (timeInstantType) {
                    QueryCriteria.TimeInstantType.CONSUMED ->
                        criteriaBuilder.and(parseOperator(timeOperator, vaultStates.get<Instant>("consumedTime"), timeValue))
                    QueryCriteria.TimeInstantType.RECORDED ->
                        criteriaBuilder.and(parseOperator(timeOperator, vaultStates.get<Instant>("recordedTime"), timeValue))
                })
        }

        return predicateSet
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
                    else -> throw VaultQueryException("Invalid query operator: $operator.")
                }
        return predicate
    }

    override fun parseCriteria(criteria: QueryCriteria.FungibleAssetQueryCriteria) : Collection<Predicate> {

        log.trace { "Parsing FungibleAssetQueryCriteria: $criteria" }

        var predicateSet = mutableSetOf<Predicate>()

        val vaultFungibleStates = criteriaQuery.from(VaultSchemaV1.VaultFungibleStates::class.java)
        rootEntities.putIfAbsent(VaultSchemaV1.VaultFungibleStates::class.java, vaultFungibleStates)
        criteriaQuery.select(vaultStates)

        val joinPredicate = criteriaBuilder.and(criteriaBuilder.equal(vaultStates.get<PersistentStateRef>("stateRef"), vaultFungibleStates.get<PersistentStateRef>("stateRef")))
        predicateSet.add(joinPredicate)

        // owner
        criteria.owner?.let {
            val ownerKeys = criteria.owner as List<AbstractParty>
            val joinFungibleStateToParty = vaultFungibleStates.join<VaultSchemaV1.VaultFungibleStates, CommonSchemaV1.Party>("issuerParty")
            val owners = ownerKeys.map { it.nameOrNull()?.toString() ?: it.toString()}
            predicateSet.add(criteriaBuilder.and(joinFungibleStateToParty.get<CommonSchemaV1.Party>("name").`in`(owners)))
        }

        // quantity
        criteria.quantity?.let {
            val operator = it.operator
            val value = it.rightOperand
            predicateSet.add(criteriaBuilder.and(parseGenericOperator(operator, vaultFungibleStates.get<Long>("quantity"), value)))
        }

        // issuer party
        criteria.issuerPartyName?.let {
            val issuerParties = criteria.issuerPartyName as List<AbstractParty>
            val joinFungibleStateToParty = vaultFungibleStates.join<VaultSchemaV1.VaultFungibleStates, CommonSchemaV1.Party>("issuerParty")
            val dealPartyKeys = issuerParties.map { it.nameOrNull().toString() }
            predicateSet.add(criteriaBuilder.equal(joinFungibleStateToParty.get<CommonSchemaV1.Party>("name"), dealPartyKeys))
        }

        // issuer reference
        criteria.issuerRef?.let {
            val issuerRefs = (criteria.issuerRef as List<OpaqueBytes>).map { it.bytes }
            predicateSet.add(criteriaBuilder.and(vaultFungibleStates.get<ByteArray>("issuerRef").`in`(issuerRefs)))
        }

        // participants
        criteria.participants?.let {
            val participants = criteria.participants as List<AbstractParty>
            val joinFungibleStateToParty = vaultFungibleStates.join<VaultSchemaV1.VaultFungibleStates, CommonSchemaV1.Party>("participants")
            val participantKeys = participants.map { it.nameOrNull().toString() }
            predicateSet.add(criteriaBuilder.and(joinFungibleStateToParty.get<CommonSchemaV1.Party>("name").`in`(participantKeys)))
            criteriaQuery.distinct(true)
        }

        return predicateSet
    }

    override fun parseCriteria(criteria: QueryCriteria.LinearStateQueryCriteria) : Collection<Predicate> {

        log.trace { "Parsing LinearStateQueryCriteria: $criteria" }

        var predicateSet = mutableSetOf<Predicate>()

        val vaultLinearStates = criteriaQuery.from(VaultSchemaV1.VaultLinearStates::class.java)
        rootEntities.putIfAbsent(VaultSchemaV1.VaultLinearStates::class.java, vaultLinearStates)
        criteriaQuery.select(vaultStates)
        val joinPredicate = criteriaBuilder.and(criteriaBuilder.equal(vaultStates.get<PersistentStateRef>("stateRef"), vaultLinearStates.get<PersistentStateRef>("stateRef")))
        joinPredicates.add(joinPredicate)

        // linear ids
        criteria.linearId?.let {
            val uniqueIdentifiers = criteria.linearId as List<UniqueIdentifier>
            val externalIds = uniqueIdentifiers.mapNotNull { it.externalId }
            if (externalIds.size > 0)
                predicateSet.add(criteriaBuilder.and(vaultLinearStates.get<String>("externalId").`in`(externalIds)))
            predicateSet.add(criteriaBuilder.and(vaultLinearStates.get<UUID>("uuid").`in`(uniqueIdentifiers.map { it.id })))
        }

        // deal refs
        criteria.dealRef?.let {
            val dealRefs = criteria.dealRef as List<String>
            predicateSet.add(criteriaBuilder.and(vaultLinearStates.get<String>("dealReference").`in`(dealRefs)))
        }

        // deal participants
        criteria.participants?.let {
            val participants = criteria.participants as List<AbstractParty>
            val joinLinearStateToParty = vaultLinearStates.join<VaultSchemaV1.VaultLinearStates, CommonSchemaV1.Party>("participants")
            val participantKeys = participants.map { it.nameOrNull().toString() }
            predicateSet.add(criteriaBuilder.and(joinLinearStateToParty.get<CommonSchemaV1.Party>("name").`in`(participantKeys)))
            criteriaQuery.distinct(true)
        }

        return predicateSet
    }

    override fun <L : Any, R : Comparable<R>> parseCriteria(criteria: QueryCriteria.VaultCustomQueryCriteria<L, R>): Collection<Predicate> {

        log.trace { "Parsing VaultCustomQueryCriteria: $criteria" }

        var predicateSet = mutableSetOf<Predicate>()

        val (entityClass, attributeName, attributeValue) = resolveKotlinOrJava(criteria.indexExpression)

        try {
            val entityRoot = criteriaQuery.from(entityClass)
            rootEntities.putIfAbsent(entityClass, entityRoot)
            criteriaQuery.select(vaultStates)
            val joinPredicate = criteriaBuilder.and(criteriaBuilder.equal(vaultStates.get<PersistentStateRef>("stateRef"), entityRoot.get<R>("stateRef")))
            joinPredicates.add(joinPredicate)

            val operator = criteria.indexExpression.operator
            if (criteria.indexExpression is UnaryLogicalExpression) {
                predicateSet.add(criteriaBuilder.and(parseGenericOperator(operator, entityRoot.get<R>(attributeName))))
            }
            else if (criteria.indexExpression is CollectionExpression) {
                @SuppressWarnings("unchecked")
                val value = attributeValue as Collection<R>
                predicateSet.add(criteriaBuilder.and(parseGenericOperator(operator, entityRoot.get<R>(attributeName), value)))
            }
            else {
                @SuppressWarnings("unchecked")
                val value = attributeValue as R
                predicateSet.add(criteriaBuilder.and(parseGenericOperator(operator, entityRoot.get<R>(attributeName), value)))
            }
        }
        catch (e: Exception) {
            e.message?.let { message ->
                if (message.contains("Not an entity"))
                    throw VaultQueryException("""
                    Please register the entity '${entityClass.name.substringBefore('$')}' class in your CorDapp's CordaPluginRegistry configuration (requiredSchemas attribute)
                    and ensure you have declared (in supportedSchemas()) and mapped (in generateMappedObject()) the schema in the associated contract state's QueryableState interface implementation.
                    See https://docs.corda.net/persistence.html?highlight=persistence for more information""")
            }
            throw VaultQueryException("Parsing error: ${e.message}")
        }

        return predicateSet
    }

    // NOTE: limitation in generics prevents using single parser for Nullable (R?) and non-Nullable (R) attribute value types
    override fun <L : Any, R : Comparable<R>> parseCriteria(criteria: QueryCriteria.VaultCustomQueryCriteriaNullable<L, R>): Collection<Predicate> {

        log.trace { "Parsing VaultCustomQueryCriteriaNullable: $criteria" }

        var predicateSet = mutableSetOf<Predicate>()

        val (entityClass, attributeName, attributeValue) = resolveKotlinOrJava(criteria.indexExpression)

        try {
            val entityRoot = criteriaQuery.from(entityClass)
            rootEntities.putIfAbsent(entityClass, entityRoot)
            criteriaQuery.select(vaultStates)
            val joinPredicate = criteriaBuilder.and(criteriaBuilder.equal(vaultStates.get<PersistentStateRef>("stateRef"), entityRoot.get<R>("stateRef")))
            joinPredicates.add(joinPredicate)

            val operator = criteria.indexExpression.operator
            if (criteria.indexExpression is UnaryLogicalExpression) {
                predicateSet.add(criteriaBuilder.and(parseGenericOperator(operator, entityRoot.get<R>(attributeName))))
            }
            else if (criteria.indexExpression is CollectionExpression) {
                @SuppressWarnings("unchecked")
                val values = attributeValue as Collection<R>
                predicateSet.add(criteriaBuilder.and(parseGenericOperator(operator, entityRoot.get<R>(attributeName), values)))
            }
            else {
                @SuppressWarnings("unchecked")
                val value = attributeValue as R
                predicateSet.add(criteriaBuilder.and(parseGenericOperator(operator, entityRoot.get<R>(attributeName), value)))
            }
        }
        catch (e: Exception) {
            e.message?.let { message ->
                if (message.contains("Not an entity"))
                    throw VaultQueryException("""
                    Please register the entity '${entityClass.name.substringBefore('$')}' class in your CorDapp's CordaPluginRegistry configuration (requiredSchemas attribute)
                    and ensure you have declared (in supportedSchemas()) and mapped (in generateMappedObject()) the schema in the associated contract state's QueryableState interface implementation.
                    See https://docs.corda.net/persistence.html?highlight=persistence for more information""")
            }
            throw VaultQueryException("Parsing error: ${e.message}")
        }

        return predicateSet
    }

    private fun resolveKotlinOrJava(expression: Logical<*, *>): Triple<Class<out PersistentState>, String, Any?> {

        val attribute = expression.leftOperand

        @SuppressWarnings("unchecked")
        val attributeClazzNameAndValue =
                when (attribute) {
                    is Field -> {
                        val value = expression.rightOperand as Object
                        Triple(attribute.declaringClass as Class<PersistentState>, attribute.name, value)
                    }
                    is KMutableProperty1<*,*> -> {
                        val attributeClazz = ((attribute as MutablePropertyReference1).owner as KClass<*>).java
                        if (expression is UnaryLogicalExpression<*>)
                            Triple(attributeClazz as Class<PersistentState>, attribute.name, null )
                        else
                            Triple(attributeClazz as Class<PersistentState>, attribute.name, expression.rightOperand )
                    }
                    else -> throw VaultQueryException("Unrecognised attribute specified: $attribute")
                }

        return attributeClazzNameAndValue
    }

    override fun parseOr(left: QueryCriteria, right: QueryCriteria): Collection<Predicate> {

        log.trace { "Parsing OR QueryCriteria composition: $left OR $right" }

        var predicateSet = mutableSetOf<Predicate>()
        val leftPredicates = parse(left)
        val rightPredicates = parse(right)

        val orPredicate = criteriaBuilder.and(criteriaBuilder.or(*leftPredicates.toTypedArray(), *rightPredicates.toTypedArray()))
        predicateSet.add(orPredicate)

        return predicateSet
    }

    override fun parseAnd(left: QueryCriteria, right: QueryCriteria): Collection<Predicate> {

        log.trace { "Parsing AND QueryCriteria composition: $left AND $right" }

        var predicateSet = mutableSetOf<Predicate>()
        val leftPredicates = parse(left)
        val rightPredicates = parse(right)

        val andPredicate = criteriaBuilder.and(criteriaBuilder.and(*leftPredicates.toTypedArray(), *rightPredicates.toTypedArray()))
        predicateSet.add(andPredicate)

        return predicateSet
    }

    private fun <T : Comparable<T>> parseGenericOperator(operator: Operator, attribute: Path<out T>?, values: Collection<T>): Predicate? {

        check(values.size > 0) { "$operator expects at least one argument value [$values]"}

        @SuppressWarnings("unchecked")
        val predicate =
                when (operator) {
                    Operator.BETWEEN -> {
                            check(values.size == 2) { "$operator expects two argument values [$values]"}
                            criteriaBuilder.between(attribute, values.first(), values.last())
                        }
                    Operator.IN -> {
                        criteriaBuilder.`in`(attribute as Expression<in T>).value(values)
                    }
                    Operator.NOT_IN -> {
                        !criteriaBuilder.`in`(attribute as Expression<in T>).value(values)
                    }
                    else -> throw VaultQueryException("Invalid query operator: $operator.")
                }
        return predicate
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
                    Operator.LIKE -> {
                        if (value is String)
                            criteriaBuilder.like(attribute as Expression<String>, value)
                        else
                            throw VaultQueryException("operator $operator expects a SQL LIKE wildcarded expression (using '%' '_') as a String ($value)")
                    }
                    Operator.NOT_LIKE -> {
                        if (value is String)
                            criteriaBuilder.notLike(attribute as Expression<String>, value)
                        else
                            throw VaultQueryException("operator $operator expects a SQL LIKE wildcarded expression (using '%' '_') as a String ($value)")
                    }
                    else -> throw VaultQueryException("Invalid query operator: $operator.")
                }
        return predicate
    }

    private fun parseGenericOperator(operator: Operator, attribute: Path<out Any>?): Predicate? {
        val predicate =
                when (operator) {
                    Operator.IS_NULL -> criteriaBuilder.isNull(attribute)
                    Operator.NOT_NULL -> criteriaBuilder.isNotNull(attribute)
                    else -> throw VaultQueryException("Invalid query operator: $operator.")
                }
        return predicate
    }

    override fun parse(criteria: QueryCriteria) : Collection<Predicate> {

        val predicateSet = criteria.visit(this)

        val combinedPredicates = joinPredicates.plus(predicateSet)
        criteriaQuery.where(*combinedPredicates.toTypedArray())

        return predicateSet
    }

    override fun parse(sorting: Sort) {

        log.trace { "Parsing sorting specification: $sorting" }

        var orderCriteria = mutableListOf<Order>()

        sorting.columns.map { (entityStateClass, entityStateColumnName, direction, nullHandling) ->
            val sortEntityRoot =
                    rootEntities.getOrElse(entityStateClass) { throw VaultQueryException("Missing root entity: $entityStateClass") }
            if (nullHandling != Sort.NullHandling.NULLS_NONE)
            // JPA Criteria does not support NULL ordering
                throw VaultQueryException("""Unsupported NULL ordering mode: $nullHandling.
                                             Current JPA implementation only supports NULLS_NONE.
                                             Watch our for our Requery implementation coming soon!""")
            when (direction) {
                Sort.Direction.ASC -> {
                    orderCriteria.add(criteriaBuilder.asc(sortEntityRoot.get<String>(entityStateColumnName)))
                }
                Sort.Direction.DESC ->
                    orderCriteria.add(criteriaBuilder.desc(sortEntityRoot.get<String>(entityStateColumnName)))
            }
        }

        if (orderCriteria.isNotEmpty()) {
            criteriaQuery.orderBy(orderCriteria)
        }
    }
}