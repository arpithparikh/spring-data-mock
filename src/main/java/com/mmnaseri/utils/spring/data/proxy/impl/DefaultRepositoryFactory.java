package com.mmnaseri.utils.spring.data.proxy.impl;

import com.mmnaseri.utils.spring.data.domain.*;
import com.mmnaseri.utils.spring.data.domain.impl.QueryDescriptionExtractor;
import com.mmnaseri.utils.spring.data.proxy.*;
import com.mmnaseri.utils.spring.data.proxy.impl.resolvers.DefaultDataOperationResolver;
import com.mmnaseri.utils.spring.data.query.DataFunctionRegistry;
import com.mmnaseri.utils.spring.data.store.DataStore;
import com.mmnaseri.utils.spring.data.store.DataStoreOperation;
import com.mmnaseri.utils.spring.data.store.DataStoreRegistry;
import com.mmnaseri.utils.spring.data.store.impl.DefaultDataStoreEventListenerContext;
import com.mmnaseri.utils.spring.data.store.impl.EventPublishingDataStore;
import com.mmnaseri.utils.spring.data.store.impl.MemoryDataStore;

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>This class is the entry point to this framework as a whole. Using this class, you can mock a repository
 * interface by passing the proper set of configurations and parameters.</p>
 *
 * @author Milad Naseri (mmnaseri@programmer.net)
 * @since 1.0 (9/29/15)
 */
public class DefaultRepositoryFactory implements RepositoryFactory {
    
    private final RepositoryMetadataResolver repositoryMetadataResolver;
    private final Map<Class<?>, RepositoryMetadata> metadataMap = new ConcurrentHashMap<>();
    private final QueryDescriptionExtractor descriptionExtractor;
    private final DataFunctionRegistry functionRegistry;
    private final DataStoreRegistry dataStoreRegistry;
    private final ResultAdapterContext adapterContext;
    private final TypeMappingContext typeMappingContext;
    private final RepositoryFactoryConfiguration configuration;
    private final NonDataOperationInvocationHandler operationInvocationHandler;

    public DefaultRepositoryFactory(RepositoryFactoryConfiguration configuration) {
        this.configuration = configuration;
        this.repositoryMetadataResolver = configuration.getRepositoryMetadataResolver();
        this.descriptionExtractor = configuration.getDescriptionExtractor();
        this.functionRegistry = configuration.getFunctionRegistry();
        this.dataStoreRegistry = configuration.getDataStoreRegistry();
        this.adapterContext = configuration.getResultAdapterContext();
        this.typeMappingContext = configuration.getTypeMappingContext();
        this.operationInvocationHandler = configuration.getOperationInvocationHandler();
    }

    @Override
    public <E> E getInstance(KeyGenerator<? extends Serializable> keyGenerator, Class<E> repositoryInterface, Class... implementations) {
        //figure out the repository metadata
        final RepositoryMetadata metadata = getRepositoryMetadata(repositoryInterface);
        //get the underlying data store
        final DataStore<Serializable, Object> dataStore = getDataStore(metadata);
        //figure out type mappings
        final List<TypeMapping<?>> typeMappings = getTypeMappings(metadata, dataStore, keyGenerator, implementations);
        //set up the data operation resolver
        final DataOperationResolver operationResolver = new DefaultDataOperationResolver(typeMappings, descriptionExtractor, metadata, functionRegistry, configuration);
        //get all of this repository's methods
        final Method[] methods = repositoryInterface.getMethods();
        //get mappings for the repository methods
        final List<InvocationMapping<? extends Serializable, ?>> invocationMappings = getInvocationMappings(operationResolver, methods);
        //extract the bound implementation types
        final List<Class<?>> boundImplementations = new LinkedList<>();
        for (TypeMapping<?> mapping : typeMappings) {
            boundImplementations.add(mapping.getType());
        }
        //set up the repository configuration
        final RepositoryConfiguration repositoryConfiguration = new ImmutableRepositoryConfiguration(metadata, keyGenerator, boundImplementations);
        //create the interceptor
        //noinspection unchecked
        final InvocationHandler interceptor = new DataOperationInvocationHandler(repositoryConfiguration, invocationMappings, dataStore, adapterContext, operationInvocationHandler);
        //create a proxy for the repository
        final Object instance = Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{repositoryInterface}, interceptor);
        //for each type mapping, inject proper dependencies
        for (TypeMapping<?> typeMapping : typeMappings) {
            if (typeMapping.getInstance() instanceof RepositoryAware<?>) {
                //noinspection unchecked
                ((RepositoryAware) typeMapping.getInstance()).setRepository(instance);
            }
            if (typeMapping.getInstance() instanceof RepositoryConfigurationAware) {
                ((RepositoryConfigurationAware) typeMapping.getInstance()).setRepositoryConfiguration(repositoryConfiguration);
            }
            if (typeMapping.getInstance() instanceof RepositoryFactoryAware) {
                ((RepositoryFactoryAware) typeMapping.getInstance()).setRepositoryFactory(this);
            }
        }
        //return the repository instance
        return repositoryInterface.cast(instance);
    }

    @Override
    public RepositoryFactoryConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * <p>Given a repository metadata, it will find out all the proper type mappings bound as implementations to the repository. These will come from the
     * {@link TypeMappingContext}, overridden by the implementations provided by the user for this specific case.</p>
     *
     * <p>If the mapped concrete class needs to know anything from the current mocking context, it can implement one of the
     * various {@link org.springframework.beans.factory.Aware aware} interfaces to be given the proper piece of contextual
     * information.</p>
     *
     * @param metadata           the repository metadata
     * @param dataStore          the data store
     * @param keyGenerator       the key generator
     * @param implementations    the implementations specified by the user
     * @return the resolved list of type mappings
     */
    private List<TypeMapping<?>> getTypeMappings(RepositoryMetadata metadata, DataStore<Serializable, Object> dataStore, KeyGenerator<? extends Serializable> keyGenerator, Class[] implementations) {
        final List<TypeMapping<?>> typeMappings = new LinkedList<>();
        final TypeMappingContext localContext = new DefaultTypeMappingContext(typeMappingContext);
        for (Class implementation : implementations) {
            localContext.register(metadata.getRepositoryInterface(), implementation);
        }
        typeMappings.addAll(localContext.getMappings(metadata.getRepositoryInterface()));
        for (TypeMapping<?> mapping : typeMappings) {
            if (mapping.getInstance() instanceof DataStoreAware<?, ?>) {
                DataStoreAware instance = (DataStoreAware<?, ?>) mapping.getInstance();
                instance.setDataStore(dataStore);
            }
            if (mapping.getInstance() instanceof RepositoryMetadataAware) {
                RepositoryMetadataAware instance = (RepositoryMetadataAware) mapping.getInstance();
                instance.setRepositoryMetadata(metadata);
            }
            if (mapping.getInstance() instanceof KeyGeneratorAware) {
                KeyGeneratorAware instance = (KeyGeneratorAware) mapping.getInstance();
                //noinspection unchecked
                instance.setKeyGenerator(keyGenerator);
            }
            if (mapping.getInstance() instanceof RepositoryFactoryConfigurationAware) {
                RepositoryFactoryConfigurationAware instance = (RepositoryFactoryConfigurationAware) mapping.getInstance();
                instance.setRepositoryFactoryConfiguration(configuration);
            }
        }
        return typeMappings;
    }

    /**
     * Given a repository interface, it will resolve the metadata for that interface.
     *
     * @param repositoryInterface    the interface
     * @param <E>                    the type of the interface
     * @return the repository metadata associated with the interface
     */
    private <E> RepositoryMetadata getRepositoryMetadata(Class<E> repositoryInterface) {
        final RepositoryMetadata metadata;
        if (metadataMap.containsKey(repositoryInterface)) {
            metadata = metadataMap.get(repositoryInterface);
        } else {
            metadata = repositoryMetadataResolver.resolve(repositoryInterface);
            metadataMap.put(repositoryInterface, metadata);
        }
        return metadata;
    }

    /**
     * <p>Given a repository metadata, it will return the data store instance associated with the entity type for that repository.</p>
     *
     * <p>If the data store is not an instance of {@link EventPublishingDataStore} it will wrap it in one, thus enabling event processing
     * for this repository.</p>
     *
     * <p>It will also register the data store instance to let the user access the data store, as well as cache it for future use.</p>
     *
     * @param metadata    the metadata
     * @return the data store
     */
    private DataStore<Serializable, Object> getDataStore(RepositoryMetadata metadata) {
        DataStore<Serializable, Object> dataStore;
        if (dataStoreRegistry.has(metadata.getEntityType())) {
            //noinspection unchecked
            dataStore = (DataStore<Serializable, Object>) dataStoreRegistry.getDataStore(metadata.getEntityType());
        } else {
            //noinspection unchecked
            dataStore = new MemoryDataStore<>((Class<Object>) metadata.getEntityType());
        }
        if (!(dataStore instanceof EventPublishingDataStore)) {
            dataStore = new EventPublishingDataStore<>(dataStore, metadata, new DefaultDataStoreEventListenerContext(configuration.getEventListenerContext()));
        }
        dataStoreRegistry.register(dataStore);
        return dataStore;
    }

    /**
     * Given a set of methods, it will rely on a {@link DataOperationResolver} to find the mappings for each of the methods.
     * @param operationResolver    the resolver to use
     * @param methods              the array of methods
     * @return resolved invocations
     */
    private List<InvocationMapping<? extends Serializable, ?>> getInvocationMappings(DataOperationResolver operationResolver, Method[] methods) {
        final List<InvocationMapping<? extends Serializable, ?>> invocationMappings = new LinkedList<>();
        for (Method method : methods) {
            final DataStoreOperation<?, ?, ?> operation = operationResolver.resolve(method);
            //noinspection unchecked
            invocationMappings.add(new ImmutableInvocationMapping<>(method, (DataStoreOperation<?, Serializable, Object>) operation));
        }
        return invocationMappings;
    }


}
