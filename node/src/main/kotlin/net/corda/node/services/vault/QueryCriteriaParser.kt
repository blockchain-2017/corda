package net.corda.node.services.vault

import io.requery.kotlin.findAttribute
import io.requery.meta.AttributeBuilder
import io.requery.query.LogicalCondition
import io.requery.query.OrderingExpression
import net.corda.core.crypto.commonName
import net.corda.core.flows.FlowException
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.*
import net.corda.node.services.vault.schemas.VaultSchema
import java.util.*
import kotlin.reflect.KMutableProperty1

class QueryCriteriaParser {

    fun parse(criteria: QueryCriteria) : LogicalCondition<*, *> {

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
                is QueryCriteria.VaultCustomQueryCriteria<*,*> -> {
                    println(criteria.javaClass.name)
                    parseCriteria(criteria)
                }
                is QueryCriteria.AndComposition -> {
                    println("AND")
                    parse(criteria.a)
                    parse(criteria.b)
                }
                is QueryCriteria.OrComposition -> {
                    println("OR")
                    parse(criteria.a)
                    parse(criteria.b)
                }
                else ->
                    throw InvalidQueryCriteriaException(criteria::class.java)
            }

        return whereClause
    }

    fun parseCriteria(criteria: QueryCriteria.VaultQueryCriteria): LogicalCondition<*, *> {
        criteria.status
        criteria.contractStateTypes
        criteria.includeSoftlockedStates
        criteria.notaryName
        criteria.participantIdentities
        criteria.stateRefs
        criteria.timeCondition

        if (criteria.timeCondition != null) {
            val timeCondition = criteria.timeCondition as LogicalExpression
            val timeInstantType = timeCondition.leftOperand
            val timeOperator = timeCondition.operator
            val timeValue = timeCondition.rightOperand
        }

        // parse state
        val attribute = findAttribute(VaultSchema.VaultStates::stateStatus)
        val attributeBuilder = AttributeBuilder<VaultSchema.VaultStates, Vault.StateStatus>(attribute.name, attribute.classType)
        val queryAttribute = attributeBuilder.build()
        val logicalCondition = queryAttribute.equal(criteria.status)

        return logicalCondition
    }

    fun parseCriteria(criteria: QueryCriteria.LinearStateQueryCriteria): LogicalCondition<*, *>  {
        criteria.linearId
        criteria.dealRef
        criteria.dealPartyName

        // UNCONSUMED by default
        val attribute = findAttribute(VaultSchema.VaultStates::stateStatus)
        val attributeBuilder = AttributeBuilder<VaultSchema.VaultStates, Vault.StateStatus>(attribute.name, attribute.classType)
        val queryAttribute = attributeBuilder.build()
        var logicalCondition : LogicalCondition<*,*> = queryAttribute.equal(Vault.StateStatus.UNCONSUMED)

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

    fun parseCriteria(criteria: QueryCriteria.FungibleAssetQueryCriteria): LogicalCondition<*, *>  {
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

        val quantityExpr : Logical<*, Long>? = criteria.quantity
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


//    fun parseCriteria(criteria: QueryCriteria.VaultCustomQueryCriteria<KMutableProperty1<*,*>,*>): LogicalCondition<*, *>  {
//    fun parseCriteria(criteria: QueryCriteria.VaultCustomQueryCriteria<KMutableProperty1<VaultSchema.VaultStates, Vault.StateStatus>, Vault.StateStatus>) : LogicalCondition<*, *> {
    fun parseCriteria(criteria: QueryCriteria.VaultCustomQueryCriteria<*,*>) : LogicalCondition<*, *> {

        val logicalExpr = criteria.indexExpression //as LogicalCondition<*,*>

        val property = logicalExpr?.leftOperand as KMutableProperty1<VaultSchema.VaultStates, Vault.StateStatus>

        val attribute = findAttribute(property)
        val attributeBuilder = AttributeBuilder<Any,Vault.StateStatus>(attribute.name, attribute.classType)
        val queryAttribute = attributeBuilder.build()

        val logicalCondition =
            when (logicalExpr?.operator) {
                Operator.EQUAL -> queryAttribute.eq(logicalExpr.rightOperand as Vault.StateStatus)
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
