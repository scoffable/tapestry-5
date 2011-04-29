// Copyright 2006, 2007, 2008, 2010, 2011 The Apache Software Foundation
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.apache.tapestry5.internal.services;

import java.util.Map;
import java.util.Set;

import org.apache.tapestry5.ComponentResources;
import org.apache.tapestry5.internal.InternalComponentResources;
import org.apache.tapestry5.internal.InternalConstants;
import org.apache.tapestry5.internal.event.InvalidationEventHubImpl;
import org.apache.tapestry5.internal.model.MutableComponentModelImpl;
import org.apache.tapestry5.internal.plastic.PlasticInternalUtils;
import org.apache.tapestry5.ioc.LoggerSource;
import org.apache.tapestry5.ioc.Resource;
import org.apache.tapestry5.ioc.internal.services.ClassFactoryImpl;
import org.apache.tapestry5.ioc.internal.services.PlasticProxyFactoryImpl;
import org.apache.tapestry5.ioc.internal.util.ClasspathResource;
import org.apache.tapestry5.ioc.internal.util.CollectionFactory;
import org.apache.tapestry5.ioc.internal.util.InternalUtils;
import org.apache.tapestry5.ioc.internal.util.URLChangeTracker;
import org.apache.tapestry5.ioc.services.ClassFactory;
import org.apache.tapestry5.ioc.services.ClasspathURLConverter;
import org.apache.tapestry5.ioc.services.PlasticProxyFactory;
import org.apache.tapestry5.model.ComponentModel;
import org.apache.tapestry5.model.MutableComponentModel;
import org.apache.tapestry5.plastic.ClassInstantiator;
import org.apache.tapestry5.plastic.InstructionBuilder;
import org.apache.tapestry5.plastic.InstructionBuilderCallback;
import org.apache.tapestry5.plastic.MethodDescription;
import org.apache.tapestry5.plastic.PlasticClass;
import org.apache.tapestry5.plastic.PlasticField;
import org.apache.tapestry5.plastic.PlasticManager;
import org.apache.tapestry5.plastic.PlasticManagerDelegate;
import org.apache.tapestry5.plastic.PlasticUtils;
import org.apache.tapestry5.runtime.Component;
import org.apache.tapestry5.runtime.ComponentResourcesAware;
import org.apache.tapestry5.services.InvalidationEventHub;
import org.apache.tapestry5.services.UpdateListener;
import org.apache.tapestry5.services.transform.ComponentClassTransformWorker2;
import org.apache.tapestry5.services.transform.TransformationSupport;
import org.slf4j.Logger;

/**
 * A wrapper around a {@link PlasticManager} that allows certain classes to be modified as they are loaded.
 */
public final class ComponentInstantiatorSourceImpl extends InvalidationEventHubImpl implements
        ComponentInstantiatorSource, UpdateListener, PlasticManagerDelegate
{

    private final Set<String> controlledPackageNames = CollectionFactory.newSet();

    private final URLChangeTracker changeTracker;

    private final ClassLoader parent;

    private final InternalRequestGlobals internalRequestGlobals;

    private final ComponentClassTransformWorker2 transformerChain;

    private final LoggerSource loggerSource;

    private final Logger logger;

    private ClassFactory classFactory;

    private PlasticProxyFactory proxyFactory;

    private PlasticManager manager;

    /**
     * Map from class name to Instantiator.
     */
    private final Map<String, Instantiator> classToInstantiator = CollectionFactory.newMap();

    private final Map<String, ComponentModel> classToModel = CollectionFactory.newMap();

    private final String[] SUBPACKAGES =
    { "." + InternalConstants.PAGES_SUBPACKAGE + ".", "." + InternalConstants.COMPONENTS_SUBPACKAGE + ".",
            "." + InternalConstants.MIXINS_SUBPACKAGE + ".", "." + InternalConstants.BASE_SUBPACKAGE + "." };

    public ComponentInstantiatorSourceImpl(boolean productionMode, Logger logger, LoggerSource loggerSource,
            ClassLoader parent, ComponentClassTransformWorker2 transformerChain,
            InternalRequestGlobals internalRequestGlobals, ClasspathURLConverter classpathURLConverter)
    {
        super(productionMode);

        this.parent = parent;
        this.transformerChain = transformerChain;
        this.logger = logger;
        this.loggerSource = loggerSource;
        this.internalRequestGlobals = internalRequestGlobals;
        this.changeTracker = new URLChangeTracker(classpathURLConverter);

        initializeService();
    }

    public synchronized void checkForUpdates()
    {
        if (!changeTracker.containsChanges())
            return;

        changeTracker.clear();
        classToInstantiator.clear();

        // Release the existing class pool, loader and so forth.
        // Create a new one.

        initializeService();

        // Tell everyone that the world has changed and they should discard
        // their cache.

        fireInvalidationEvent();
    }

    /**
     * Invoked at object creation, or when there are updates to class files (i.e., invalidation), to create a new set of
     * Javassist class pools and loaders.
     */
    private void initializeService()
    {
        manager = new PlasticManager(parent, this, controlledPackageNames);

        classFactory = new ClassFactoryImpl(manager.getClassLoader(), logger);

        proxyFactory = new PlasticProxyFactoryImpl(classFactory, manager.getClassLoader());

        classToInstantiator.clear();
        classToModel.clear();
    }

    public synchronized Instantiator getInstantiator(final String className)
    {
        Instantiator result = classToInstantiator.get(className);

        if (result == null)
        {
            // Force the creation of the class (and the transformation of the class). This will first
            // trigger transformations of any base classes.

            final ClassInstantiator<Component> plasticInstantiator = manager.getClassInstantiator(className);

            final ComponentModel model = classToModel.get(className);

            result = new Instantiator()
            {
                public Component newInstance(InternalComponentResources resources)
                {
                    return plasticInstantiator.with(ComponentResources.class, resources)
                            .with(InternalComponentResources.class, resources).newInstance();
                }

                public ComponentModel getModel()
                {
                    return model;
                }

                @Override
                public String toString()
                {
                    return String.format("[Instantiator[%s]", className);
                }
            };

            classToInstantiator.put(className, result);
        }

        return result;
    }

    // synchronized may be overkill, but that's ok.
    public synchronized void addPackage(String packageName)
    {
        assert InternalUtils.isNonBlank(packageName);

        controlledPackageNames.add(packageName);
    }

    public boolean exists(String className)
    {
        return parent.getResource(PlasticInternalUtils.toClassPath(className)) != null;
    }

    public ClassFactory getClassFactory()
    {
        return classFactory;
    }

    public PlasticProxyFactory getProxyFactory()
    {
        return proxyFactory;
    }

    public InvalidationEventHub getInvalidationEventHub()
    {
        return this;
    }

    public void transform(PlasticClass plasticClass)
    {
        String className = plasticClass.getClassName();
        String parentClassName = plasticClass.getSuperClassName();

        // The parent model may not exist, if the super class is not in a controlled package.

        ComponentModel parentModel = classToModel.get(parentClassName);

        final boolean isRoot = parentModel == null;

        if (isRoot
                && !(parentClassName.equals("java.lang.Object") || parentClassName
                        .equals("groovy.lang.GroovyObjectSupport")))
        {
            String suggestedPackageName = buildSuggestedPackageName(className);

            throw new RuntimeException(ServicesMessages.baseClassInWrongPackage(parentClassName, className,
                    suggestedPackageName));
        }

        // Tapestry 5.2 was more sensitive that the parent class have a public no-args constructor. Plastic
        // doesn't care, and we don't have the tools to dig that information out.

        Logger logger = loggerSource.getLogger(className);

        Resource baseResource = new ClasspathResource(PlasticInternalUtils.toClassPath(className));

        changeTracker.add(baseResource.toURL());

        if (isRoot)
        {
            implementComponentInterface(plasticClass);
        }

        MutableComponentModel model = new MutableComponentModelImpl(plasticClass.getClassName(), logger, baseResource,
                parentModel);

        transformerChain.transform(plasticClass, new TransformationSupport()
        {
            public Class toClass(String typeName)
            {
                try
                {
                    return PlasticInternalUtils.toClass(manager.getClassLoader(), typeName);
                }
                catch (ClassNotFoundException ex)
                {
                    throw new RuntimeException(String.format("Unable to convert type '%s' to a Class: %s", typeName,
                            InternalUtils.toMessage(ex)), ex);
                }
            }

            public boolean isRootTransformation()
            {
                return isRoot;
            }
        }, model);

        classToModel.put(className, model);
    }

    private MethodDescription GET_COMPONENT_RESOURCES = new MethodDescription(PlasticUtils.getMethod(
            ComponentResourcesAware.class, "getComponentResources"));

    private void implementComponentInterface(PlasticClass plasticClass)
    {
        plasticClass.introduceInterface(Component.class);

        final PlasticField resourcesField = plasticClass.introduceField(InternalComponentResources.class,
                "internalComponentResources").injectFromInstanceContext();

        plasticClass.introduceMethod(GET_COMPONENT_RESOURCES, new InstructionBuilderCallback()
        {
            public void doBuild(InstructionBuilder builder)
            {
                builder.loadThis().getField(resourcesField).returnResult();
            }
        });
    }

    public <T> ClassInstantiator<T> configureInstantiator(String className, ClassInstantiator<T> instantiator)
    {
        return instantiator;
    }

    private String buildSuggestedPackageName(String className)
    {
        for (String subpackage : SUBPACKAGES)
        {
            int pos = className.indexOf(subpackage);

            // Keep the leading '.' in the subpackage name and tack on "base".

            if (pos > 0)
                return className.substring(0, pos + 1) + InternalConstants.BASE_SUBPACKAGE;
        }

        // Is this even reachable? className should always be in a controlled package and so
        // some subpackage above should have matched.

        return null;
    }
}
