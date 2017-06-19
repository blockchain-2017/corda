package net.corda.core.node.services.vault

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateRef
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.StatePersistable
import net.corda.core.serialization.CordaSerializable
import kotlin.reflect.KProperty1

@CordaSerializable
enum class Operator {
    AND,
    OR,
    EQUAL,
    NOT_EQUAL,
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    IN,
    NOT_IN,
    LIKE,
    NOT_LIKE,
    BETWEEN,
    IS_NULL,
    NOT_NULL
}

interface Condition<L, R> {
    val leftOperand: L
    val operator: Operator
    val rightOperand: R
}

interface AndOr<out Q> {
    infix fun <V> and(condition: Condition<V, *>): Logical<*, *>
    infix fun <V> or(condition: Condition<V, *>): Logical<*, *>
}

@CordaSerializable
sealed class Logical<L, R> : Condition<L, R>, AndOr<L>

class LogicalExpression<L, R>(leftOperand: L,
                              operator: Operator,
                              rightOperand: R?) : Logical<L, R>() {
    init {
        if (rightOperand == null) {
            check(operator in setOf(Operator.NOT_NULL, Operator.IS_NULL),
                    { "Must use a unary operator (${Operator.IS_NULL}, ${Operator.NOT_NULL}) if right operand is null"} )
        }
        else {
            check(operator !in setOf(Operator.NOT_NULL, Operator.IS_NULL),
                    { "Cannot use a unary operator (${Operator.IS_NULL}, ${Operator.NOT_NULL}) if right operand is not null"} )
        }
    }

    override val operator: Operator = operator
    override val rightOperand: R = rightOperand as R
    override val leftOperand: L = leftOperand

    override fun <V> and(condition: Condition<V, *>): Logical<*, *> {
        return LogicalExpression(this, Operator.AND, condition)
    }

    override fun <V> or(condition: Condition<V, *>): Logical<*, *> {
        return LogicalExpression(this, Operator.OR, condition)
    }
}


/**
 *  Pagination and Ordering
 *
 *  Provide simple ability to specify an offset within a result set and the number of results to
 *  return from that offset (eg. page size) together with (optional) sorting criteria at column level.
 *
 *  Note: it is the responsibility of the calling client to manage page windows.
 *
 *  For advanced pagination it is recommended you utilise standard JPA query frameworks such as
 *  Spring Data's JPARepository which extends the [PagingAndSortingRepository] interface to provide
 *  paging and sorting capability:
 *  https://docs.spring.io/spring-data/commons/docs/current/api/org/springframework/data/repository/PagingAndSortingRepository.html
 */
val DEFAULT_PAGE_NUM = 0
val DEFAULT_PAGE_SIZE = 200

/**
 * Note: this maximum size will be configurable in future (to allow for large JVM heap sized node configurations)
 *       Use [PageSpecification] to correctly handle a number of bounded pages of [MAX_PAGE_SIZE].
 */
val MAX_PAGE_SIZE = 512

/**
 * PageSpecification allows specification of a page number (starting from 0 as default) and page size (defaulting to
 * [DEFAULT_PAGE_SIZE] with a maximum page size of [MAX_PAGE_SIZE]
 */
@CordaSerializable
data class PageSpecification(val pageNumber: Int = DEFAULT_PAGE_NUM, val pageSize: Int = DEFAULT_PAGE_SIZE)

/**
 * Sort allows specification of a set of entity attribute names and their associated directionality
 * and null handling, to be applied upon processing a query specification.
 */
@CordaSerializable
data class Sort(val columns: Collection<SortColumn>) {
    @CordaSerializable
    enum class Direction {
        ASC,
        DESC
    }
    @CordaSerializable
    enum class NullHandling {
        NULLS_FIRST,
        NULLS_LAST,
        NULLS_NONE
    }
    /**
     * [columnName] should reference a persisted entity attribute name as defined by the associated mapped schema
     * (for example, [VaultSchema.VaultStates::txId.name])
     */
    @CordaSerializable
    data class SortColumn(val entityStateClass: Class<out PersistentState>,
                          val entityStateColumnName: String,
                          val direction: Sort.Direction = Sort.Direction.ASC,
                          val nullHandling: Sort.NullHandling = NullHandling.NULLS_NONE)
    // TODO: JPA Criteria does not support NULL handling
    // Change the default when we switch to either Hibernate (native) or Requery which both support NULLS handling in their respective Criteria API's
    //  if (direction == Sort.Direction.ASC) Sort.NullHandling.NULLS_LAST else Sort.NullHandling.NULLS_FIRST)
}

/**
 * Helper method to generate a string formatted list of Composite Keys for Requery Expression clause
 */
fun stateRefArgs(stateRefs: List<StateRef>): List<List<Any>> {
    return stateRefs.map { listOf("'${it.txhash}'", it.index) }
}

inline fun <reified T: ContractState> deriveContractTypes(): Set<Class<out ContractState>> = deriveContractTypes(T::class.java)

fun <T: ContractState> deriveContractTypes(contractType: Class<T>?): Set<Class<out ContractState>> {
    if (contractType == null)
        return setOf(ContractState::class.java)
    else
        return setOf(contractType)
}

