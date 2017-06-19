package net.corda.node.services.vault

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.toBase58String
import net.corda.core.identity.AnonymousParty
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.IQueryCriteriaParser
import net.corda.core.node.services.vault.Operator
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.Sort
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.PersistentStateRef
import net.corda.core.serialization.OpaqueBytes
import net.corda.core.serialization.toHexString
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

    // incrementally build list of join predicates
    private var joinPredicates = mutableListOf<Predicate>()
    // incrementally build list of root entities (for later use in Sort parsing)
    private var rootEntities = mutableMapOf<Class<out PersistentState>, Root<*>>()

    override fun parseCriteria(criteria: QueryCriteria.VaultQueryCriteria) : Collection<Predicate> {

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
        combinedContractTypeTypes?.filter { it.name != ContractState::class.java.name }?.let {
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
            val timeOperator = timeCondition?.operator
            val timeValue = timeCondition?.rightOperand
            predicateSet.add(
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
                    else -> throw InvalidQueryOperatorException(operator)
                }
        return predicate
    }

    override fun parseCriteria(criteria: QueryCriteria.FungibleAssetQueryCriteria) : Collection<Predicate> {

        var predicateSet = mutableSetOf<Predicate>()

        val vaultFungibleStates = criteriaQuery.from(VaultSchemaV1.VaultFungibleStates::class.java)
        rootEntities.putIfAbsent(VaultSchemaV1.VaultFungibleStates::class.java, vaultFungibleStates)
        criteriaQuery.select(vaultStates)

        val joinPredicate = criteriaBuilder.and(criteriaBuilder.equal(vaultStates.get<PersistentStateRef>("stateRef"), vaultFungibleStates.get<PersistentStateRef>("stateRef")))
        predicateSet.add(joinPredicate)

        // owner
        criteria.owner?.let {
            val ownerKeys = criteria.owner as List<PublicKey>
            val joinFungibleStateToParty = vaultFungibleStates.join<VaultSchemaV1.VaultFungibleStates, CommonSchemaV1.Party>("issuerParty")
            val owners = ownerKeys.map { it.toBase58String() }
            predicateSet.add(criteriaBuilder.equal(joinFungibleStateToParty.get<CommonSchemaV1.Party>("key"), owners))
        }

        // quantity
        criteria.quantity?.let {
            val operator = it.operator
            val value = it.rightOperand
            predicateSet.add(criteriaBuilder.and(parseGenericOperator(operator, vaultFungibleStates.get<Long>("quantity"), value)))
        }

        // issuer party
        criteria.issuerPartyName?.let {
            val issuerParties = criteria.issuerPartyName as List<AnonymousParty>
            val joinFungibleStateToParty = vaultFungibleStates.join<VaultSchemaV1.VaultFungibleStates, CommonSchemaV1.Party>("issuerParty")
            val dealPartyKeys = issuerParties.map { it.owningKey.toBase58String() }
            predicateSet.add(criteriaBuilder.equal(joinFungibleStateToParty.get<CommonSchemaV1.Party>("key"), dealPartyKeys))
        }

        // issuer reference
        criteria.issuerRef?.let {
            val issuerRefs = (criteria.issuerRef as List<OpaqueBytes>).map { it.bytes }
            predicateSet.add(criteriaBuilder.and(vaultFungibleStates.get<ByteArray>("issuerRef").`in`(issuerRefs)))
        }

        // exit keys
        criteria.exitKeys?.let {
            val exitPKs = criteria.exitKeys as List<PublicKey>
            val joinFungibleStateToParty = vaultFungibleStates.join<VaultSchemaV1.VaultFungibleStates, CommonSchemaV1.Party>("issuerParty")
            val exitKeys = exitPKs.map { it.toBase58String() }
            predicateSet.add(criteriaBuilder.equal(joinFungibleStateToParty.get<CommonSchemaV1.Party>("key"), exitKeys))
        }

        return predicateSet
    }

    override fun parseCriteria(criteria: QueryCriteria.LinearStateQueryCriteria) : Collection<Predicate> {

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

        // deal parties
        criteria.dealParties?.let {
            val dealParties = criteria.dealParties as List<AnonymousParty>
            val joinLinearStateToParty = vaultLinearStates.join<VaultSchemaV1.VaultLinearStates, CommonSchemaV1.Party>("dealParties")
            val dealPartyKeys = dealParties.map { it.owningKey.toBase58String() }
            predicateSet.add(criteriaBuilder.equal(joinLinearStateToParty.get<CommonSchemaV1.Party>("key"), dealPartyKeys))
        }

        return predicateSet
    }

    override fun <L : Any, R : Comparable<R>> parseCriteria(criteria: QueryCriteria.VaultCustomQueryCriteria<L, R>): Collection<Predicate> {

        var predicateSet = mutableSetOf<Predicate>()

        val (entityClass, attributeName) = resolveIt(criteria.indexExpression.leftOperand)

        try {
            val entityRoot = criteriaQuery.from(entityClass)
            rootEntities.putIfAbsent(entityClass as Class<PersistentState>, entityRoot)
            criteriaQuery.select(vaultStates)
            val joinPredicate = criteriaBuilder.and(criteriaBuilder.equal(vaultStates.get<PersistentStateRef>("stateRef"), entityRoot.get<R>("stateRef")))
            joinPredicates.add(joinPredicate)

            val operator = criteria.indexExpression.operator
            val value = criteria.indexExpression.rightOperand // TODO: Java Test failing here (.toString().javaClass)

            predicateSet.add(criteriaBuilder.and(parseGenericOperator(operator, entityRoot.get<R>(attributeName), value)))
        }
        catch (e: Exception) {
            e?.message?.let { message ->
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

    private fun resolveIt(attribute: Any): Pair<Class<out Any>, String> {

        val attributeClazzAndName : Pair<Class<out Any>, String> =
            when (attribute) {
                is Field -> {
                    Pair(attribute.declaringClass, attribute.name)
                }
                is KMutableProperty1<*,*> -> {
                    val entity = (attribute as MutablePropertyReference1).owner
                    Pair((attribute.owner as KClass<*>).java, attribute.name)
                }
                else -> throw VaultQueryException("Unrecognised attribute specified: $attribute")
            }
        return attributeClazzAndName
    }

    override fun parseOr(left: QueryCriteria, right: QueryCriteria): Collection<Predicate> {

        var predicateSet = mutableSetOf<Predicate>()
        val leftPredicates = parse(left)
        val rightPredicates = parse(right)

        val orPredicate = criteriaBuilder.and(criteriaBuilder.or(*leftPredicates.toTypedArray(), *rightPredicates.toTypedArray()))
        predicateSet.add(orPredicate)

        return predicateSet
    }

    override fun parseAnd(left: QueryCriteria, right: QueryCriteria): Collection<Predicate> {

        var predicateSet = mutableSetOf<Predicate>()
        val leftPredicates = parse(left)
        val rightPredicates = parse(right)

        val andPredicate = criteriaBuilder.and(criteriaBuilder.and(*leftPredicates.toTypedArray(), *rightPredicates.toTypedArray()))
        predicateSet.add(andPredicate)

        return predicateSet
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

    override fun parse(criteria: QueryCriteria) : Collection<Predicate> {

        val predicateSet = criteria.visit(this)

        val combinedPredicates = joinPredicates.plus(predicateSet)
        criteriaQuery.where(*combinedPredicates.toTypedArray())

        return predicateSet
    }

    override fun parse(sorting: Sort) {

        var orderCriteria = mutableListOf<Order>()

        sorting.columns.map { (entityStateClass, entityStateColumnName, direction) ->
            val sortEntityRoot =
                    rootEntities.getOrElse(entityStateClass) { throw VaultQueryException("Missing root entity: $entityStateClass") }
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