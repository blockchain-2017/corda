package net.corda.node.services.vault

import io.requery.kotlin.*
import io.requery.kotlin.Selection
import io.requery.meta.Attribute
import io.requery.meta.AttributeBuilder
import io.requery.meta.AttributeDelegate
import io.requery.meta.Type
import io.requery.query.*
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateRef
import net.corda.core.node.services.Vault
import net.corda.core.node.services.VaultQueryException
import net.corda.core.node.services.vault.*
import net.corda.core.node.services.vault.Logical
import net.corda.core.node.services.vault.Operator
import net.corda.core.schemas.StatePersistable
import net.corda.core.utilities.loggerFor
import net.corda.node.services.contract.schemas.requery.CommercialPaperSchemaV3
import net.corda.node.services.vault.schemas.requery.VaultSchema
import net.corda.node.services.vault.schemas.requery.VaultStatesEntity
import org.bouncycastle.asn1.x500.X500Name
import java.time.Instant
import javax.persistence.criteria.Predicate
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.javaMethod

class RequeryQueryCriteriaParser(val contractTypeMappings: Map<String, List<String>>, val query: Selection<out Result<VaultSchema.VaultStates>>) : IQueryCriteriaParser {
    override fun <L : Any, R : Comparable<R>> parseCriteria(criteria: QueryCriteria.VaultCustomQueryCriteriaNullable<L, R>): Collection<Predicate> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    companion object {
        val log = loggerFor<RequeryQueryCriteriaParser>()

        // Define composite primary key used in Requery Expression
        val stateRefCompositeColumn: RowExpression = RowExpression.of(listOf(VaultStatesEntity.TX_ID, VaultStatesEntity.INDEX))

        /**
         * Duplicate code: non-reified implementation of Requery findAttribute
         */
        fun <T : Any, R> findAttribute(clazz: Class<out T>, property: KProperty1<T, R>):
                AttributeDelegate<T, R> {
            val type: Type<*>? = AttributeDelegate.types
                    .filter { type -> (type.classType == clazz || type.baseType == clazz)}
                    .firstOrNull()

            if (type == null) {
                throw UnsupportedOperationException(clazz.simpleName + "." + property.name + " cannot be used in query")
            }

            val attribute: Attribute<*, *>? = type.attributes
                    .filter { attribute -> attribute.propertyName.replaceFirst("get", "")
                            .equals(property.name, ignoreCase = true) }.firstOrNull()

            if (attribute !is AttributeDelegate) {
                throw UnsupportedOperationException(clazz.simpleName + "." + property.name + " cannot be used in query")
            }
            @Suppress("UNCHECKED_CAST")
            return attribute as AttributeDelegate<T, R>
        }
    }

    override fun parse(criteria: QueryCriteria): Collection<Predicate> {
        return criteria.visit(this)
    }

    override fun parseCriteria(criteria: QueryCriteria.VaultQueryCriteria): Collection<Predicate> {

        // state status
        val attribute = findAttribute(VaultSchema.VaultStates::stateStatus).get()
        var chainedConditions : LogicalCondition<*, *> =
                if (criteria.status == Vault.StateStatus.ALL)
                    attribute.`in`(setOf(Vault.StateStatus.UNCONSUMED, Vault.StateStatus.CONSUMED))
                else
                    attribute.equal(criteria.status)

        // contract State Types
        criteria.contractStateTypes?.filter { it.name != ContractState::class.java.name }?.let {
            val interfaces = it.flatMap { contractTypeMappings[it.name] ?: emptyList() }
            val concrete = it.filter { !it.isInterface }.map { it.name }
            val all = interfaces.plus(concrete)
            if (all.isNotEmpty())
                chainedConditions = chainedConditions.and(VaultSchema.VaultStates::contractStateClassName `in` (all))
        }

        // soft locking
        if (!criteria.includeSoftlockedStates)
            chainedConditions = chainedConditions.and(VaultSchema.VaultStates::lockId.isNull())

        // notary names
        criteria.notaryName?.let {
            val attributeNotary = findAttribute(VaultSchema.VaultStates::notaryName).get()
            val notaryNames = (criteria.notaryName as List<X500Name>).map { it.toString() }  // why does this need a cast?
            chainedConditions = chainedConditions.and(attributeNotary.`in`(notaryNames))
        }

        // state references
        criteria.stateRefs?.let {
            val stateRefArgs = stateRefArgs(criteria.stateRefs!!)
            chainedConditions = chainedConditions.and(stateRefCompositeColumn.`in`(stateRefArgs))
        }

        // time constraints (recorded, consumed)
        if (criteria.timeCondition != null) {
            val timeCondition = criteria.timeCondition as LogicalExpression
            val timeInstantType = timeCondition.leftOperand
            val timeOperator = timeCondition.operator
            val timeValue = timeCondition.rightOperand
            when (timeInstantType) {
                QueryCriteria.TimeInstantType.CONSUMED ->
                    chainedConditions = chainedConditions.and(parseOperator(VaultSchema.VaultStates::consumedTime, timeOperator, timeValue))
                QueryCriteria.TimeInstantType.RECORDED ->
                    chainedConditions = chainedConditions.and(parseOperator(VaultSchema.VaultStates::recordedTime, timeOperator, timeValue))
            }
        }

        query.where(chainedConditions)

        return emptySet()
    }

    private fun parseOperator(property: KProperty1<VaultSchema.VaultStates, Instant?>, operator: Operator, value: Array<Instant>): io.requery.kotlin.Logical<out Expression<Instant?>, out Any?> {
        val condition =
            when (operator) {
                Operator.EQUAL -> property.eq(value[0])
                Operator.NOT_EQUAL -> property.ne(value[0])
                Operator.GREATER_THAN -> property.gt(value[0])
                Operator.GREATER_THAN_OR_EQUAL -> property.gte(value[0])
                Operator.LESS_THAN -> property.lt(value[0])
                Operator.LESS_THAN_OR_EQUAL -> property.lte(value[0])
                Operator.BETWEEN -> property.between(value[0],value[1])
                else -> throw VaultQueryException("Invalid query operator: $operator.")
            }
        return condition
    }

    override fun parseCriteria(criteria: QueryCriteria.LinearStateQueryCriteria): Collection<Predicate> {

        // UNCONSUMED by default
        val attribute = findAttribute(VaultSchema.VaultStates::stateStatus).get()
        var logicalCondition: LogicalCondition<*, *> = attribute.equal(Vault.StateStatus.UNCONSUMED)

        // parse linearId
        logicalCondition = logicalCondition.and(
                criteria.linearId?.let {
                    val attribute1 = findAttribute(VaultSchema.VaultLinearState::externalId).get()
                    val attribute2 = findAttribute(VaultSchema.VaultLinearState::uuid).get()
                    val logicalCondition1 = attribute1.`in`(criteria.linearId?.map { it.externalId })
                    val logicalCondition2 = attribute2.`in`(criteria.linearId?.map { it.id })
                    logicalCondition1.and(logicalCondition2)
                })

        // deal ref
        logicalCondition?.and(
            criteria.dealRef?.let {
                val attribute = findAttribute(VaultSchema.VaultDealState::ref).get()
                attribute.`in`(criteria.dealRef)
            })

        // deal parties
//        logicalCondition?.and(
//                criteria.participants?.let {
//                    val attribute = findAttribute(VaultSchema.VaultDealState::parties).get()
//                    val parties = it.map { it }.toSet()
//                    attribute.eq(parties)
//                })

        if (logicalCondition == null)
            throw VaultQueryException("No query criteria specified for ${QueryCriteria.LinearStateQueryCriteria::class.java.simpleName}")

        query.where(logicalCondition)

        return emptySet()
    }

    override fun parseCriteria(criteria: QueryCriteria.FungibleAssetQueryCriteria): Collection<Predicate> {
        criteria.quantity
        criteria.issuerPartyName
        criteria.issuerRef
        criteria.owner
        criteria.exitKeys

        // parse quantity
        val attribute = findAttribute(VaultSchema.VaultFungibleState::quantity).get()

        val quantityExpr: Logical<*, Long>? = criteria.quantity
        quantityExpr?.let {
            val logicalCondition = attribute.greaterThan(it.rightOperand)
            query.where(logicalCondition)
        }

        return emptySet()
    }

    override fun <L: Any, R : Comparable<R>> parseCriteria(criteria: QueryCriteria.VaultCustomQueryCriteria<L, R>): Collection<Predicate> {

        val logicalExpr = criteria.indexExpression
        val property = logicalExpr?.leftOperand!!

        val attribute = findAttribute(property.getter.javaMethod!!.declaringClass as Class<L>, property)
        val queryAttribute = attribute.get()

        val logicalCondition =
                when (logicalExpr?.operator) {
                    Operator.EQUAL -> queryAttribute.eq(logicalExpr.rightOperand)
                    Operator.NOT_EQUAL -> queryAttribute.ne(logicalExpr.rightOperand)
                    Operator.GREATER_THAN -> queryAttribute.gt(logicalExpr.rightOperand)
                    Operator.GREATER_THAN_OR_EQUAL -> queryAttribute.gte(logicalExpr.rightOperand)
//                    Operator.AND -> queryAttribute.and(logicalExpr.rightOperand)
//                    Operator.OR -> queryAttribute.or(logicalExpr.rightOperand)
                    Operator.LESS_THAN -> queryAttribute.lt(logicalExpr.rightOperand)
                    Operator.LESS_THAN_OR_EQUAL -> queryAttribute.lte(logicalExpr.rightOperand)
                    Operator.BETWEEN -> {
                        val multiValue = logicalExpr.rightOperand as Collection<R>
                        queryAttribute.between(multiValue.first(), multiValue.last())
                    }
                    Operator.IN -> queryAttribute.`in`(logicalExpr.rightOperand)
                    Operator.NOT_IN -> queryAttribute.`notIn`(logicalExpr.rightOperand)
                    Operator.LIKE -> queryAttribute.like(logicalExpr.rightOperand as String)
                    Operator.NOT_LIKE -> queryAttribute.notLike(logicalExpr.rightOperand as String)
                    Operator.IS_NULL -> queryAttribute.isNull
                    Operator.NOT_NULL -> queryAttribute.notNull()
                    else -> {
                        throw VaultQueryException("Invalid query operator: ${logicalExpr?.operator}.")
                    }
                }

        query.where(logicalCondition)

        return emptySet()
    }

    override fun parse(sorting: Sort) {
        val orderByExpressions: MutableList<OrderingExpression<*>> = mutableListOf()
        sorting.columns.map {
            orderByExpressions.add(parseSorting(it))
        }
        query.orderBy(*orderByExpressions.toTypedArray())
    }

    private fun parseSorting(sortColumn: Sort.SortColumn): OrderingExpression<*> {

        val attribute = AttributeBuilder<VaultSchema.VaultStates, String>(sortColumn.entityStateColumnName, sortColumn.entityStateColumnName.javaClass)

        val orderingExpression =
                when (sortColumn.direction) {
                    Sort.Direction.ASC -> if (sortColumn.nullHandling == Sort.NullHandling.NULLS_FIRST)
                        attribute.asc().nullsFirst() else attribute.asc().nullsLast()
                    Sort.Direction.DESC -> if (sortColumn.nullHandling == Sort.NullHandling.NULLS_FIRST)
                        attribute.desc().nullsFirst() else attribute.desc().nullsLast()
                }

        return orderingExpression
    }

    fun deriveEntities(criteria: QueryCriteria): List<Class<out StatePersistable>> {

        val entityClasses : MutableSet<Class<out StatePersistable>> = mutableSetOf()

        when (criteria) {
            is QueryCriteria.VaultQueryCriteria ->
                entityClasses.add(VaultSchema.VaultStates::class.java)

            is QueryCriteria.FungibleAssetQueryCriteria ->
                entityClasses.add(VaultSchema.VaultFungibleState::class.java)

            is QueryCriteria.LinearStateQueryCriteria ->
                entityClasses.add(VaultSchema.VaultLinearState::class.java)

            is QueryCriteria.VaultCustomQueryCriteria<*, *> ->
                entityClasses.add(CommercialPaperSchemaV3.PersistentCommercialPaperState3::class.java)

            is QueryCriteria.AndComposition -> {
                println("AND")
                entityClasses.addAll(deriveEntities(criteria.a))
                entityClasses.addAll(deriveEntities(criteria.b))
            }
            is QueryCriteria.OrComposition -> {
                println("OR")
                entityClasses.addAll(deriveEntities(criteria.a))
                entityClasses.addAll(deriveEntities(criteria.b))
            }
            else ->
                throw VaultQueryException("No query criteria specified for ${criteria::class.java.simpleName}")
        }

        return setOf(VaultSchema.VaultStates::class.java).plus(entityClasses).toList()
    }

    override fun parseOr(left: QueryCriteria, right: QueryCriteria): Collection<Predicate> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun parseAnd(left: QueryCriteria, right: QueryCriteria): Collection<Predicate> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun stateRefArgs(stateRefs: List<StateRef>): List<List<Any>> {
        return stateRefs.map { listOf("'${it.txhash}'", it.index) }
    }
}
