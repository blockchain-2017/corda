package net.corda.node.services.vault

import io.requery.kotlin.*
import io.requery.meta.AttributeBuilder
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
import net.corda.core.utilities.loggerFor
import net.corda.node.services.vault.schemas.VaultSchema
import net.corda.node.services.vault.schemas.VaultStatesEntity
import java.time.Instant
import java.util.*
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1

//inline fun <reified T : ContractState> QueryCriteriaParser.parse(criteria: QueryCriteria) =
//    parse(criteria, deriveContractTypes<T>())

class QueryCriteriaParser {

    private companion object {
        val log = loggerFor<QueryCriteriaParser>()

        // Define composite primary key used in Requery Expression
        val stateRefCompositeColumn: RowExpression = RowExpression.of(listOf(VaultStatesEntity.TX_ID, VaultStatesEntity.INDEX))
    }

    fun parse(criteria: QueryCriteria) : LogicalCondition<*, *> {

//        if (criteria is QueryCriteria.VaultQueryCriteria ) {
//            criteria.contractStateTypes?.plus(contractTypes) ?: setOf(contractTypes)
//            criteria.status?.let { Vault.StateStatus.UNCONSUMED }
//        }

        val whereClause : LogicalCondition<*, *> =

            when (criteria) {
                is QueryCriteria.VaultQueryCriteria -> {
                    println(criteria.javaClass.name)
                    parseCriteria(criteria)
                }
                is QueryCriteria.FungibleAssetQueryCriteria -> {
                    println(criteria.javaClass.name)
                    parseCriteria(criteria)
                }
                is QueryCriteria.LinearStateQueryCriteria -> {
                    println(criteria.javaClass.name)
                    parseCriteria(criteria)
                }
//                is QueryCriteria.VaultCustomQueryCriteria<*,*> -> {
//                    println(criteria.javaClass.name)
//                    parseCriteria(criteria)
//                }
                is QueryCriteria.AndComposition -> {
                    println("AND")
                    val left: LogicalCondition<*,*> = parse(criteria.a)
                    val right: LogicalCondition<*,*> = parse(criteria.b)
                    return left.and(right)
                }
                is QueryCriteria.OrComposition -> {
                    println("OR")
                    val left: LogicalCondition<*,*> = parse(criteria.a)
                    val right: LogicalCondition<*,*> = parse(criteria.b)
                    return left.or(right)
                }
                else ->
                    throw InvalidQueryCriteriaException(criteria::class.java)
            }

        return whereClause
    }

    inline fun <reified T: ContractState> deriveContractTypes(): Set<Class<out ContractState>> = deriveContractTypes(T::class.java)

    fun <T: ContractState> deriveContractTypes(contractType: Class<T>?): Set<Class<out ContractState>> {
        if (contractType == null)
            return setOf(ContractState::class.java)
        else
            return setOf(contractType)
    }

    fun parseCriteria(criteria: QueryCriteria.VaultQueryCriteria): LogicalCondition<*, *> {
        criteria.status
        criteria.contractStateTypes
        criteria.includeSoftlockedStates
        criteria.notaryName
        criteria.participantIdentities
        criteria.stateRefs
        criteria.timeCondition

        // state status
        val attribute = findAttribute(VaultSchema.VaultStates::stateStatus)
        val attributeBuilder = AttributeBuilder<VaultSchema.VaultStates, Vault.StateStatus>(attribute.name, attribute.classType)
        val queryAttribute = attributeBuilder.build()
        var chainedConditions : LogicalCondition<*, *> =
                if (criteria.status == Vault.StateStatus.ALL)
                    queryAttribute.`in`(setOf(Vault.StateStatus.UNCONSUMED, Vault.StateStatus.CONSUMED))
                else
                    queryAttribute.equal(criteria.status)

        // contract State Types
        criteria.contractStateTypes?.let {
            if (!it.map { it.name }.contains(ContractState::class.java.name))
                chainedConditions = chainedConditions.and(VaultSchema.VaultStates::contractStateClassName `in` (it.map { it.name }))
        }

        // soft locking
        if (!criteria.includeSoftlockedStates)
            chainedConditions = chainedConditions.and(VaultSchema.VaultStates::lockId.isNull())

        // notary names
        criteria.notaryName?.let {
            val attributeNotary = findAttribute(VaultSchema.VaultStates::notaryName)
            val queryAttributeNotary = AttributeBuilder<VaultSchema.VaultStates, String>(attributeNotary.name, attributeNotary.classType).build()
            chainedConditions = chainedConditions.and(queryAttributeNotary.`in`(criteria.notaryName.toString()))
        }

        // state references
        criteria.stateRefs?.let {
            val stateRefArgs = stateRefArgs(criteria.stateRefs!!)
            chainedConditions = chainedConditions.and(stateRefCompositeColumn.`in`(stateRefArgs))
//            it.forEach {
//                val attributeTxId = findAttribute(VaultSchema.VaultStates::txId)
//                val attributeIndex = findAttribute(VaultSchema.VaultStates::index)
//                chainedConditions = chainedConditions.and(VaultSchema.VaultStates::txId eq it.txhash.toString())
//                chainedConditions = chainedConditions.and(VaultSchema.VaultStates::index eq it.index)
//            }
        }

        // time constraints (recorded, consumed, lockUpdate)
        if (criteria.timeCondition != null) {
//            val attributeTimestamp = findAttribute(VaultSchema.VaultStates::consumedTime)
//            val attributeTimestamp = findAttribute(VaultSchema.VaultStates::recordedTime)
//            val attributeTimestamp = findAttribute(VaultSchema.VaultStates::lockUpdateTime)

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

    fun parseCriteria(criteria: QueryCriteria.LinearStateQueryCriteria): LogicalCondition<*, *> {
        criteria.linearId
        criteria.dealRef
        criteria.dealPartyName

        // UNCONSUMED by default
        val attribute = findAttribute(VaultSchema.VaultStates::stateStatus)
        val attributeBuilder = AttributeBuilder<VaultSchema.VaultStates, Vault.StateStatus>(attribute.name, attribute.classType)
        val queryAttribute = attributeBuilder.build()
        var logicalCondition: LogicalCondition<*, *> = queryAttribute.equal(Vault.StateStatus.UNCONSUMED)

        // parse linearId
        logicalCondition = logicalCondition.and(
                criteria.linearId?.let {
                    val attribute1 = findAttribute(VaultSchema.VaultLinearState::externalId)
                    val attribute2 = findAttribute(VaultSchema.VaultLinearState::uuid)

                    val attributeBuilder1 = AttributeBuilder<VaultSchema.VaultLinearState, String>(attribute1.name, attribute1.classType)
                    val attributeBuilder2 = AttributeBuilder<VaultSchema.VaultLinearState, UUID>(attribute2.name, attribute2.classType)

                    val queryAttribute1 = attributeBuilder1.build()
                    val queryAttribute2 = attributeBuilder2.build()

                    val logicalCondition1 = queryAttribute1.`in`(criteria.linearId?.map { it.externalId })
                    val logicalCondition2 = queryAttribute2.`in`(criteria.linearId?.map { it.id })
                    logicalCondition1.and(logicalCondition2)
                })



        // deal ref
        logicalCondition?.and(
            criteria.dealRef?.let {
                val attribute = findAttribute(VaultSchema.VaultDealState::ref)
                val attributeBuilder = AttributeBuilder<VaultSchema.VaultDealState, String>(attribute.name, attribute.classType)
                val queryAttribute = attributeBuilder.build()
                queryAttribute.`in`(criteria.dealRef)
            })

        // deal parties
        logicalCondition?.and(
                criteria.dealPartyName?.let {
                    val attribute = findAttribute(VaultSchema.VaultDealState::partyNames)
                    val attributeBuilder = AttributeBuilder<VaultSchema.VaultDealState, Set<String>>(attribute.name, attribute.classType)
//                    val attributeBuilder = AttributeBuilder<VaultSchema.VaultDealState, Set<VaultSchema.VaultParty>>(attribute.name, attribute.classType)
                    val queryAttribute = attributeBuilder.build()
                    val parties = it.map { it.commonName }.toSet()
//                    val parties = it.map {
//                                val party = VaultPartyEntity()
//                                party.name = it.nameOrNull()?.commonName
//                                party.key = it.owningKey.toBase58String()
//                                party
//                            }.toSet()
                    queryAttribute.eq(parties)
                })

//        if (logicalCondition.count() <= 1)
        if (logicalCondition == null)
            throw InvalidQueryCriteriaException(QueryCriteria.LinearStateQueryCriteria::class.java)

        return logicalCondition
    }

    fun parseCriteria(criteria: QueryCriteria.FungibleAssetQueryCriteria): LogicalCondition<*, *> {
        criteria.tokenType
        criteria.tokenValue
        criteria.quantity
        criteria.issuerPartyName
        criteria.issuerRef
        criteria.ownerIdentity
        criteria.exitKeyIdentity

        // parse quantity
        val attribute = findAttribute(VaultSchema.VaultFungibleState::quantity)
        val attributeBuilder = AttributeBuilder<VaultSchema.VaultFungibleState, Long>(attribute.name, attribute.classType)
        val queryAttribute = attributeBuilder.build()

        val quantityExpr: Logical<*, Long>? = criteria.quantity
        quantityExpr?.let {
            it.leftOperand
            it.operator
            it.rightOperand

            val logicalCondition = queryAttribute.greaterThan(it.rightOperand)
            return logicalCondition
        }

//        val logicalCondition = queryAttribute.equal(criteria.quantity)
//        val attributeDelegate = AttributeDelegate(queryAttribute)
//
//        return criteria.quantity
        throw RuntimeException()
    }

    inline fun <reified L : Any, R>  parseCriteria(criteria: QueryCriteria.VaultCustomQueryCriteria<KMutableProperty1<L, R>, R>): LogicalCondition<*, *> {

        val logicalExpr = criteria.indexExpression
        val property = logicalExpr?.leftOperand!!

        val attribute = findAttribute(property)
        val attributeBuilder = AttributeBuilder<Any, R>(attribute.name, attribute.classType)
        val queryAttribute = attributeBuilder.build()

        val logicalCondition =
                when (logicalExpr?.operator) {
                    Operator.EQUAL -> queryAttribute.eq(logicalExpr.rightOperand)
                    else -> {
                        throw InvalidQueryOperatorException(logicalExpr!!.operator)
                    }
                }

        return logicalCondition
    }

    inline fun <reified L : Any, R> parseMe(criteria: QueryCriteria.VaultCustomQueryCriteria<KMutableProperty1<L, R>,*>) {

        val logicalExpr = criteria.indexExpression
        val property = logicalExpr?.leftOperand!!
        val attribute = findAttribute(property)
        println(attribute)
    }

    inline fun <reified L : Any, R> parseMe2(expression: LogicalExpression<KMutableProperty1<L, R>,*>) {
        val property = expression?.leftOperand!!
        val attribute = findAttribute(property)
        println(attribute)
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

class InvalidQueryCriteriaException(criteriaMissing: Class<out QueryCriteria>) : FlowException("No query criteria specified for ${criteriaMissing.simpleName}.")
class InvalidQueryOperatorException(operator: Operator) : FlowException("Invalid query operator: $operator.")
class UnsupportedQueryException(description: String) : FlowException("Unsupported query: $description.")
