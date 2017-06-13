package net.corda.core.node.services.vault

import net.corda.core.contracts.Commodity
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria.AndComposition
import net.corda.core.node.services.vault.QueryCriteria.OrComposition
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.OpaqueBytes
import org.bouncycastle.asn1.x500.X500Name
import java.security.PublicKey
import java.time.Instant
import java.util.*
import javax.persistence.criteria.Predicate
import kotlin.reflect.KMutableProperty1

/**
 * Indexing assumptions:
 * QueryCriteria assumes underlying schema tables are correctly indexed for performance.
 */
@CordaSerializable
sealed class QueryCriteria {

    abstract fun visit(parser: IQueryCriteriaParser): Collection<Predicate>

    /**
     * VaultQueryCriteria: provides query by attributes defined in [VaultSchema.VaultStates]
     */
    data class VaultQueryCriteria @JvmOverloads constructor (
            val status: Vault.StateStatus = Vault.StateStatus.UNCONSUMED,
            val contractStateTypes: Set<Class<out ContractState>>? = null,
            val stateRefs: List<StateRef>? = null,
            val notaryName: List<X500Name>? = null,
            val includeSoftlockedStates: Boolean = true,
            val timeCondition: Logical<TimeInstantType, Array<Instant>>? = null) : QueryCriteria() {

        override fun visit(parser: IQueryCriteriaParser): Collection<Predicate> {
            return parser.parseCriteria(this)
        }
    }

    /**
     * LinearStateQueryCriteria: provides query by attributes defined in [VaultSchema.VaultLinearState]
     */
    data class LinearStateQueryCriteria @JvmOverloads constructor(
            val participants: List<AbstractParty>? = null,
            val linearId: List<UniqueIdentifier>? = null,
            val dealRef: List<String>? = null) : QueryCriteria() {

        override fun visit(parser: IQueryCriteriaParser): Collection<Predicate> {
            return parser.parseCriteria(this)
        }
    }

   /**
    * FungibleStateQueryCriteria: provides query by attributes defined in [VaultSchema.VaultFungibleState]
    *
    * Valid TokenType implementations defined by Amount<T> are
    *   [Currency] as used in [Cash] contract state
    *   [Commodity] as used in [CommodityContract] state
    */
    data class FungibleAssetQueryCriteria @JvmOverloads constructor(
           val participants: List<AbstractParty>? = null,
           val owner: List<AbstractParty>? = null,
           val quantity: Logical<*,Long>? = null,
           val issuerPartyName: List<AbstractParty>? = null,
           val issuerRef: List<OpaqueBytes>? = null) : QueryCriteria() {

       override fun visit(parser: IQueryCriteriaParser): Collection<Predicate> {
           return parser.parseCriteria(this)
       }
   }

    /**
     * VaultCustomQueryCriteria: provides query by custom attributes defined in a contracts
     * [QueryableState] implementation.
     * (see Persistence documentation for more information)
     *
     * Params
     *  [indexExpression] refers to a (composable) JPA Query like WHERE expression clauses of the form:
     *      [JPA entityAttributeName] [Operand] [Value]
     *
     * Refer to [CommercialPaper.State] for a concrete example.
     */
    data class VaultCustomQueryCriteria<L: Any, R : Comparable<R>>(val indexExpression: Logical<KMutableProperty1<L,R>, out R>) : QueryCriteria() {

        override fun visit(parser: IQueryCriteriaParser): Collection<Predicate> {
            return parser.parseCriteria(this)
        }
    }

    data class VaultCustomQueryCriteriaNullable<L: Any, R : Comparable<R>>(val indexExpression: Logical<KMutableProperty1<L,R?>, out R>) : QueryCriteria() {

        override fun visit(parser: IQueryCriteriaParser): Collection<Predicate> {
            return parser.parseCriteria(this)
        }
    }

    // enable composition of [QueryCriteria]
    data class AndComposition(val a: QueryCriteria, val b: QueryCriteria): QueryCriteria() {

        override fun visit(parser: IQueryCriteriaParser): Collection<Predicate> {
            return parser.parseAnd(this.a, this.b)
        }
    }

    data class OrComposition(val a: QueryCriteria, val b: QueryCriteria): QueryCriteria() {
        override fun visit(parser: IQueryCriteriaParser): Collection<Predicate> {
            return parser.parseOr(this.a, this.b)
        }
    }

    // timestamps stored in the vault states table [VaultSchema.VaultStates]
    @CordaSerializable
    enum class TimeInstantType {
        RECORDED,
        CONSUMED
    }
}

interface IQueryCriteriaParser {
    fun parseCriteria(criteria: QueryCriteria.FungibleAssetQueryCriteria): Collection<Predicate>
    fun parseCriteria(criteria: QueryCriteria.LinearStateQueryCriteria): Collection<Predicate>
    fun <L: Any,R : Comparable<R>> parseCriteria(criteria: QueryCriteria.VaultCustomQueryCriteria<L, R>): Collection<Predicate>
    fun <L: Any,R : Comparable<R>> parseCriteria(criteria: QueryCriteria.VaultCustomQueryCriteriaNullable<L, R>): Collection<Predicate>
    fun parseCriteria(criteria: QueryCriteria.VaultQueryCriteria): Collection<Predicate>
    fun parseOr(left: QueryCriteria, right: QueryCriteria): Collection<Predicate>
    fun parseAnd(left: QueryCriteria, right: QueryCriteria): Collection<Predicate>

    fun parse(criteria: QueryCriteria) : Collection<Predicate>
    fun parse(sorting: Sort)
}

infix fun QueryCriteria.and(criteria: QueryCriteria): QueryCriteria = AndComposition(this, criteria)
infix fun QueryCriteria.or(criteria: QueryCriteria): QueryCriteria = OrComposition(this, criteria)
