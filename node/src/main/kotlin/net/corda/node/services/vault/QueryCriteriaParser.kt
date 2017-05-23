package net.corda.node.services.vault

import io.requery.kotlin.*
import io.requery.meta.Attribute
import io.requery.meta.AttributeBuilder
import io.requery.meta.AttributeDelegate
import io.requery.meta.Type
import io.requery.query.Expression
import io.requery.query.LogicalCondition
import io.requery.query.OrderingExpression
import io.requery.query.RowExpression
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.commonName
import net.corda.core.flows.FlowException
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.*
import net.corda.core.node.services.vault.Logical
import net.corda.core.schemas.StatePersistable
import net.corda.core.utilities.loggerFor
import net.corda.node.services.contract.schemas.CommercialPaperSchemaV2
import net.corda.node.services.contract.schemas.CashSchemaV2
import net.corda.node.services.contract.schemas.DummyLinearStateSchemaV2
import net.corda.node.services.vault.schemas.VaultSchema
import net.corda.node.services.vault.schemas.VaultStatesEntity
import org.bouncycastle.asn1.x500.X500Name
import java.time.Instant
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.javaMethod

class QueryCriteriaParser(val contractTypeMappings: Map<String, List<String>>) : IQueryCriteriaParser {

    companion object {
        val log = loggerFor<QueryCriteriaParser>()

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

    override fun parse(criteria: QueryCriteria) : LogicalCondition<*,*> {
        return criteria.visit(this)
    }

    inline fun <reified T: ContractState> deriveContractTypes(): Set<Class<out ContractState>> = deriveContractTypes(T::class.java)

    fun <T: ContractState> deriveContractTypes(contractType: Class<T>?): Set<Class<out ContractState>> {
        if (contractType == null)
            return setOf(ContractState::class.java)
        else
            return setOf(contractType)
    }

    override fun parseCriteria(criteria: QueryCriteria.VaultQueryCriteria): LogicalCondition<*, *> {

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

        // participants (are associated with all ContractState types but not stored in the Vault States table - should they?)
        criteria.participantIdentities?.let {
            throw UnsupportedQueryException("Unable to query on contract state participants until identity schemas defined")
        }

        return chainedConditions
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
                else -> throw InvalidQueryOperatorException(operator)
            }
        return condition
    }

    override fun parseCriteria(criteria: QueryCriteria.LinearStateQueryCriteria): LogicalCondition<*, *> {

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
        logicalCondition?.and(
                criteria.dealPartyName?.let {
                    val attribute = findAttribute(VaultSchema.VaultDealState::partyNames).get()
                    val parties = it.map { it.commonName }.toSet()
                    attribute.eq(parties)
                })

        if (logicalCondition == null)
            throw InvalidQueryCriteriaException(QueryCriteria.LinearStateQueryCriteria::class.java)

        return logicalCondition
    }

    override fun parseCriteria(criteria: QueryCriteria.FungibleAssetQueryCriteria): LogicalCondition<*, *> {
        criteria.tokenType
        criteria.tokenValue
        criteria.quantity
        criteria.issuerPartyName
        criteria.issuerRef
        criteria.ownerIdentity
        criteria.exitKeyIdentity

        // parse quantity
        val attribute = findAttribute(VaultSchema.VaultFungibleState::quantity).get()

        val quantityExpr: Logical<*, Long>? = criteria.quantity
        quantityExpr?.let {
            val logicalCondition = attribute.greaterThan(it.rightOperand)
            return logicalCondition
        }

        throw UnsupportedQueryException("Specified criteria: $criteria")

    }

    override fun <L: Any, R> parseCriteria(criteria: QueryCriteria.VaultCustomQueryCriteria<L, R>): LogicalCondition<*, *> {

        val logicalExpr = criteria.indexExpression
        val property = logicalExpr?.leftOperand!!

        val attribute = findAttribute(property.getter.javaMethod!!.declaringClass as Class<L>, property)
        val queryAttribute = attribute.get()

        val logicalCondition =
                when (logicalExpr?.operator) {
                    Operator.EQUAL -> queryAttribute.eq(logicalExpr.rightOperand)
                    Operator.GREATER_THAN_OR_EQUAL -> queryAttribute.gte(logicalExpr.rightOperand)
                    else -> {
                        throw InvalidQueryOperatorException(logicalExpr!!.operator)
                    }
                }

        return logicalCondition
    }

    fun parseSorting(sortColumn: Sort.SortColumn): OrderingExpression<*> {

        val attribute = AttributeBuilder<VaultSchema.VaultStates, String>(sortColumn.columnName, sortColumn.columnName.javaClass)

        val orderingExpression =
                when (sortColumn.direction) {
                    Sort.Direction.ASC -> if (sortColumn.nullHandling == Sort.NullHandling.NULLS_FIRST)
                        attribute.asc().nullsFirst() else attribute.asc().nullsLast()
                    Sort.Direction.DESC -> if (sortColumn.nullHandling == Sort.NullHandling.NULLS_FIRST)
                        attribute.desc().nullsFirst() else attribute.desc().nullsLast()
                }

        return orderingExpression
    }

    /**
     * Helper method to generate a string formatted list of Composite Keys for Requery Expression clause
     */
    private fun stateRefArgs(stateRefs: List<StateRef>): List<List<Any>> {
        return stateRefs.map { listOf("'${it.txhash}'", it.index) }
    }

    fun deriveEntities(criteria: QueryCriteria): List<Class<out StatePersistable>> {

        val entityClasses : MutableSet<Class<out StatePersistable>> = mutableSetOf()

        when (criteria) {
            is QueryCriteria.VaultQueryCriteria -> println("SKIP")
                // entityClasses.add(VaultSchema.VaultStates::class.java)

            is QueryCriteria.FungibleAssetQueryCriteria ->
                entityClasses.add(CashSchemaV2.PersistentCashState2::class.java)

            is QueryCriteria.LinearStateQueryCriteria -> {
                entityClasses.add(DummyLinearStateSchemaV2.PersistentDummyLinearState2::class.java)
            }

            is QueryCriteria.VaultCustomQueryCriteria<*, *> -> {
                entityClasses.add(CommercialPaperSchemaV2.PersistentCommercialPaperState2::class.java)
            }

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
                throw InvalidQueryCriteriaException(criteria::class.java)
        }

        return setOf(VaultSchema.VaultStates::class.java).plus(entityClasses).toList()
    }
}

fun  <L, R> LogicalCondition<L, R>.count(): Int {
    var size = 1
    if (this.operator.equals(io.requery.query.Operator.AND)) {
        if (this.leftOperand is LogicalCondition<*, *>) {
            size += (this.leftOperand as LogicalCondition<*, *>).count()
        }
        if (this.rightOperand is LogicalCondition<*, *>) {
            size += (this.rightOperand as LogicalCondition<*, *>).count()
        }
    }
    else return 0
    return size
}

class VaultQueryException(description: String) : FlowException("Vault query: $description.")
class InvalidQueryCriteriaException(criteriaMissing: Class<out QueryCriteria>) : FlowException("No query criteria specified for ${criteriaMissing.simpleName}.")
class InvalidQueryOperatorException(operator: Operator) : FlowException("Invalid query operator: $operator.")
class UnsupportedQueryException(description: String) : FlowException("Unsupported query: $description.")
