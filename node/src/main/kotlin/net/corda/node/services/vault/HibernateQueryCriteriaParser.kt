package net.corda.node.services.vault

import net.corda.core.contracts.ContractState
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.IQueryCriteriaParser
import net.corda.core.node.services.vault.Operator
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.stateRefArgs
import net.corda.core.schemas.PersistentStateRef
import org.bouncycastle.asn1.x500.X500Name
import java.time.Instant
import javax.persistence.criteria.CriteriaBuilder
import javax.persistence.criteria.Path
import javax.persistence.criteria.Predicate


class HibernateQueryCriteriaParser(val criteriaBuilder: CriteriaBuilder, val contractTypeMappings: Map<String, List<String>>) : IQueryCriteriaParser {

    override fun parseCriteria(criteria: QueryCriteria.VaultQueryCriteria) {

        val criteriaQuery = criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)
        val vaultStates = criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)

        // state status
        criteriaQuery.where(
            if (criteria.status == Vault.StateStatus.ALL)
                vaultStates.get<Vault.StateStatus>("stateStatus").`in`(setOf(Vault.StateStatus.UNCONSUMED, Vault.StateStatus.CONSUMED))
            else
                criteriaBuilder.equal(vaultStates.get<Vault.StateStatus>("stateStatus"), criteria.status)
        )

        // contract State Types
        criteria.contractStateTypes?.filter { it.name != ContractState::class.java.name }?.let {
            val interfaces = it.flatMap { contractTypeMappings[it.name] ?: emptyList() }
            val concrete = it.filter { !it.isInterface }.map { it.name }
            val all = interfaces.plus(concrete)
            if (all.isNotEmpty())
                criteriaBuilder.and(vaultStates.get<String>("contractStateClassName").`in`(all))
        }

        // soft locking
        if (!criteria.includeSoftlockedStates)
            criteriaBuilder.and(vaultStates.get<String>("lockId").isNull)

        // notary names
        criteria.notaryName?.let {
            val notaryNames = (criteria.notaryName as List<X500Name>).map { it.toString() }
            criteriaBuilder.and(vaultStates.get<String>("notaryName").`in`(notaryNames))
        }

        // state references
        criteria.stateRefs?.let {
            val stateRefArgs = stateRefArgs(criteria.stateRefs!!)
            val compositeKey = vaultStates.get<PersistentStateRef>("stateRef")
            criteriaBuilder.and(compositeKey.`in`(stateRefArgs))
        }

        // time constraints (recorded, consumed)
        criteria.timeCondition?.let {
            val timeCondition = criteria.timeCondition
            val timeInstantType = timeCondition!!.leftOperand
            val timeOperator = timeCondition?.operator
            val timeValue = timeCondition?.rightOperand
            when (timeInstantType) {
                QueryCriteria.TimeInstantType.CONSUMED ->
                    criteriaBuilder.and(parseOperator(timeOperator, vaultStates.get<Instant>("consumedTime"), timeValue))
                QueryCriteria.TimeInstantType.RECORDED ->
                    criteriaBuilder.and(parseOperator(timeOperator, vaultStates.get<Instant>("recordedTime"), timeValue))
            }
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
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun parseCriteria(criteria: QueryCriteria.LinearStateQueryCriteria) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun <L : Any, R> parseCriteria(criteria: QueryCriteria.VaultCustomQueryCriteria<L, R>) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun parse(criteria: QueryCriteria) {
        criteria.visit(this)
    }
}