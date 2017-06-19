package net.corda.node.services.schema

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.FungibleAsset
import net.corda.core.contracts.LinearState
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.node.services.api.SchemaService
import net.corda.node.services.vault.schemas.jpa.CommonSchemaV1
import net.corda.node.services.vault.schemas.jpa.VaultSchemaV1
import net.corda.schemas.CashSchemaV1
import net.corda.schemas.CashSchemaV2
import net.corda.schemas.CashSchemaV3

/**
 * Most basic implementation of [SchemaService].
 *
 * TODO: support loading schema options from node configuration.
 * TODO: support configuring what schemas are to be selected for persistence.
 * TODO: support plugins for schema version upgrading or custom mapping not supported by original [QueryableState].
 * TODO: create whitelisted tables when a CorDapp is first installed
 */
class NodeSchemaService : SchemaService, SingletonSerializeAsToken() {
    // Currently does not support configuring schema options.

    // Whitelisted tables are those required by internal Corda services
    // For example, cash is used by the vault for coin selection
    // This whitelist will grow as we add further functionality (eg. other fungible assets)
    override val schemaOptions: Map<MappedSchema, SchemaService.SchemaOptions> = mapOf(Pair(CashSchemaV1, SchemaService.SchemaOptions()),
                                                                                       Pair(CommonSchemaV1, SchemaService.SchemaOptions()),
                                                                                       /*Pair(CashSchemaV3, SchemaService.SchemaOptions()),*/
                                                                                       Pair(VaultSchemaV1, SchemaService.SchemaOptions(tablePrefix = "")))

    // Currently returns all schemas supported by the state, with no filtering or enrichment.
    override fun selectSchemas(state: ContractState): Iterable<MappedSchema> {
        val schemas = mutableSetOf<MappedSchema>()
        if (state is QueryableState)
            schemas += state.supportedSchemas()
        if (state is LinearState)
            schemas += VaultSchemaV1   // VaultLinearStates
        if (state is FungibleAsset<*>)
            schemas += VaultSchemaV1   // VaultFungibleStates

        return schemas
    }

    // Because schema is always one supported by the state, just delegate.
    override fun generateMappedObject(state: ContractState, schema: MappedSchema): PersistentState {
        if ((schema is VaultSchemaV1) && (state is LinearState))
            return VaultSchemaV1.VaultLinearStates(state.linearId)
        if ((schema is VaultSchemaV1) && (state is FungibleAsset<*>))
            return VaultSchemaV1.VaultFungibleStates(state.amount.quantity)
        return (state as QueryableState).generateMappedObject(schema)
    }
}
