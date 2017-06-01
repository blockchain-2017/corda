package net.corda.node.services.database

import net.corda.core.schemas.MappedSchema
import net.corda.core.utilities.debug
import net.corda.core.utilities.loggerFor
import net.corda.node.services.api.SchemaService
import org.hibernate.SessionFactory
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.model.naming.Identifier
import org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl
import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder
import org.hibernate.cfg.Configuration
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment
import org.hibernate.service.UnknownUnwrapTypeException
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

class HibernateConfiguration(val schemaService: SchemaService) {
    companion object {
        val logger = loggerFor<HibernateConfiguration>()
    }

    // TODO: make this a guava cache or similar to limit ability for this to grow forever.
    val sessionFactories = ConcurrentHashMap<MappedSchema, SessionFactory>()

    init {
        schemaService.schemaOptions.map { it.key }.forEach {
            makeSessionFactoryForSchema(it)
        }
    }

    fun sessionFactoryForSchema(schema: MappedSchema): SessionFactory {
        return sessionFactories.computeIfAbsent(schema, { makeSessionFactoryForSchema(it) })
    }

    fun sessionFactoryForSchemas(vararg schemas: MappedSchema): SessionFactory {
        return makeSessionFactoryForSchemas(schemas.iterator())
    }

    private fun makeSessionFactoryForSchema(schema: MappedSchema): SessionFactory {
        return makeSessionFactoryForSchemas(setOf(schema).iterator())
//        logger.info("Creating session factory for schema $schema")
//        val serviceRegistry = BootstrapServiceRegistryBuilder().build()
//        val metadataSources = MetadataSources(serviceRegistry)
//        // We set a connection provider as the auto schema generation requires it.  The auto schema generation will not
//        // necessarily remain and would likely be replaced by something like Liquibase.  For now it is very convenient though.
//        // TODO: replace auto schema generation as it isn't intended for production use, according to Hibernate docs.
//        val config = Configuration(metadataSources).setProperty("hibernate.connection.provider_class", HibernateConfiguration.NodeDatabaseConnectionProvider::class.java.name)
//                .setProperty("hibernate.hbm2ddl.auto", "update")
//                .setProperty("hibernate.show_sql", "true")
//                .setProperty("hibernate.format_sql", "true")
//        val options = schemaService.schemaOptions[schema]
//        val databaseSchema = options?.databaseSchema
//        if (databaseSchema != null) {
//            logger.debug { "Database schema = $databaseSchema" }
//            config.setProperty("hibernate.default_schema", databaseSchema)
//        }
//        val tablePrefix = options?.tablePrefix ?: "contract_" // We always have this as the default for aesthetic reasons.
//        logger.debug { "Table prefix = $tablePrefix" }
//        schema.mappedTypes.forEach { config.addAnnotatedClass(it) }
//        val sessionFactory = buildSessionFactory(config, metadataSources, tablePrefix)
//        logger.info("Created session factory for schema $schema")
//        return sessionFactory
    }

    private fun makeSessionFactoryForSchemas(schemas: Iterator<MappedSchema>): SessionFactory {
        logger.info("Creating session factory for schemas: $schemas")
        val serviceRegistry = BootstrapServiceRegistryBuilder().build()
        val metadataSources = MetadataSources(serviceRegistry)
        // We set a connection provider as the auto schema generation requires it.  The auto schema generation will not
        // necessarily remain and would likely be replaced by something like Liquibase.  For now it is very convenient though.
        // TODO: replace auto schema generation as it isn't intended for production use, according to Hibernate docs.
        val config = Configuration(metadataSources).setProperty("hibernate.connection.provider_class", HibernateConfiguration.NodeDatabaseConnectionProvider::class.java.name)
                .setProperty("hibernate.hbm2ddl.auto", "update")
                .setProperty("hibernate.show_sql", "true")
                .setProperty("hibernate.format_sql", "true")
        schemas.forEach { schema ->
            schema.mappedTypes.forEach { config.addAnnotatedClass(it) }
        }
        val sessionFactory = buildSessionFactory(config, metadataSources, "")
        logger.info("Created session factory for schemas: $schemas")
        return sessionFactory
    }

    private fun buildSessionFactory(config: Configuration, metadataSources: MetadataSources, tablePrefix: String): SessionFactory {
        config.standardServiceRegistryBuilder.applySettings(config.properties)
        val metadata = metadataSources.getMetadataBuilder(config.standardServiceRegistryBuilder.build()).run {
            applyPhysicalNamingStrategy(object : PhysicalNamingStrategyStandardImpl() {
                override fun toPhysicalTableName(name: Identifier?, context: JdbcEnvironment?): Identifier {
                    val default = super.toPhysicalTableName(name, context)
                    return Identifier.toIdentifier(tablePrefix + default.text, default.isQuoted)
                }
            })
            build()
        }

        return metadata.sessionFactoryBuilder.run {
            allowOutOfTransactionUpdateOperations(true)
            applySecondLevelCacheSupport(false)
            applyQueryCacheSupport(false)
            enableReleaseResourcesOnCloseEnabled(true)
            build()
        }
    }

    // Supply Hibernate with connections from our underlying Exposed database integration.  Only used
    // during schema creation / update.
    class NodeDatabaseConnectionProvider : ConnectionProvider {
        override fun closeConnection(conn: Connection) {
            val tx = TransactionManager.current()
            tx.commit()
            tx.close()
        }

        override fun supportsAggressiveRelease(): Boolean = true

        override fun getConnection(): Connection {
            val tx = TransactionManager.manager.newTransaction(Connection.TRANSACTION_REPEATABLE_READ)
            return tx.connection
        }

        override fun <T : Any?> unwrap(unwrapType: Class<T>): T {
            try {
                return unwrapType.cast(this)
            } catch(e: ClassCastException) {
                throw UnknownUnwrapTypeException(unwrapType)
            }
        }

        override fun isUnwrappableAs(unwrapType: Class<*>?): Boolean = (unwrapType == NodeDatabaseConnectionProvider::class.java)
    }
}