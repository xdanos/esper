/**************************************************************************************
 * Copyright (C) 2006-2015 EsperTech Inc. All rights reserved.                        *
 * http://www.espertech.com/esper                                                          *
 * http://www.espertech.com                                                           *
 * ---------------------------------------------------------------------------------- *
 * The software in this package is published under the terms of the GPL license       *
 * a copy of which has been included with this distribution in the license.txt file.  *
 **************************************************************************************/
package com.espertech.esper.epl.core;

import com.espertech.esper.client.*;
import com.espertech.esper.client.annotation.BuiltinAnnotation;
import com.espertech.esper.client.hook.AggregationFunctionFactory;
import com.espertech.esper.client.util.ClassForNameProvider;
import com.espertech.esper.collection.Pair;
import com.espertech.esper.epl.agg.access.AggregationStateType;
import com.espertech.esper.epl.agg.factory.AggregationFactoryFactory;
import com.espertech.esper.epl.approx.CountMinSketchAggType;
import com.espertech.esper.epl.expression.accessagg.ExprAggCountMinSketchNode;
import com.espertech.esper.epl.expression.accessagg.ExprAggMultiFunctionLinearAccessNode;
import com.espertech.esper.epl.expression.accessagg.ExprAggMultiFunctionSortedMinMaxByNode;
import com.espertech.esper.epl.expression.core.ExprCurrentEvaluationContextNode;
import com.espertech.esper.epl.expression.core.ExprNode;
import com.espertech.esper.epl.expression.methodagg.*;
import com.espertech.esper.epl.expression.time.TimeAbacus;
import com.espertech.esper.type.MinMaxTypeEnum;
import com.espertech.esper.util.TransientConfigurationResolver;
import com.espertech.esper.util.JavaClassHelper;
import com.espertech.esper.util.MethodResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.MathContext;
import java.util.*;

/**
 * Implementation for engine-level imports.
 */
public class EngineImportServiceImpl implements EngineImportService
{
    private static final Logger log = LoggerFactory.getLogger(EngineImportServiceImpl.class);

	private final List<String> imports;
    private final List<String> annotationImports;
    private final Map<String, ConfigurationPlugInAggregationFunction> aggregationFunctions;
    private final List<Pair<Set<String>, ConfigurationPlugInAggregationMultiFunction>> aggregationAccess;
    private final Map<String, EngineImportSingleRowDesc> singleRowFunctions;
    private final Map<String, ConfigurationMethodRef> methodInvocationRef;
    private final boolean allowExtendedAggregationFunc;
    private final boolean isUdfCache;
    private final boolean isDuckType;
    private final boolean sortUsingCollator;
    private final MathContext optionalDefaultMathContext;
    private final TimeZone timeZone;
    private final TimeAbacus timeAbacus;
    private final ConfigurationEngineDefaults.ThreadingProfile threadingProfile;
    private final Map<String, Object> transientConfiguration;
    private final AggregationFactoryFactory aggregationFactoryFactory;

	public EngineImportServiceImpl(boolean allowExtendedAggregationFunc, boolean isUdfCache, boolean isDuckType, boolean sortUsingCollator, MathContext optionalDefaultMathContext, TimeZone timeZone, TimeAbacus timeAbacus, ConfigurationEngineDefaults.ThreadingProfile threadingProfile, Map<String, Object> transientConfiguration, AggregationFactoryFactory aggregationFactoryFactory)
    {
        imports = new ArrayList<String>();
        annotationImports = new ArrayList<String>(2);
        aggregationFunctions = new HashMap<String, ConfigurationPlugInAggregationFunction>();
        aggregationAccess = new ArrayList<Pair<Set<String>, ConfigurationPlugInAggregationMultiFunction>>();
        singleRowFunctions = new HashMap<String, EngineImportSingleRowDesc>();
        methodInvocationRef = new HashMap<String, ConfigurationMethodRef>();
        this.allowExtendedAggregationFunc = allowExtendedAggregationFunc;
        this.isUdfCache = isUdfCache;
        this.isDuckType = isDuckType;
        this.sortUsingCollator = sortUsingCollator;
        this.optionalDefaultMathContext = optionalDefaultMathContext;
        this.timeZone = timeZone;
        this.timeAbacus = timeAbacus;
        this.threadingProfile = threadingProfile;
        this.transientConfiguration = transientConfiguration;
        this.aggregationFactoryFactory = aggregationFactoryFactory;
    }

    public boolean isUdfCache() {
        return isUdfCache;
    }

    public boolean isDuckType() {
        return isDuckType;
    }

    public ConfigurationMethodRef getConfigurationMethodRef(String className)
    {
        return methodInvocationRef.get(className);
    }

    public ClassForNameProvider getClassForNameProvider() {
        return TransientConfigurationResolver.resolveClassForNameProvider(transientConfiguration);
    }

    public ClassLoader getFastClassClassLoader(Class clazz) {
        return TransientConfigurationResolver.resolveFastClassClassLoaderProvider(transientConfiguration).classloader(clazz);
    }

    public ClassLoader getClassLoader() {
        return TransientConfigurationResolver.resolveClassLoader(transientConfiguration).classloader();
    }

    /**
     * Adds cache configs for method invocations for from-clause.
     * @param configs cache configs
     */
    public void addMethodRefs(Map<String, ConfigurationMethodRef> configs)
    {
        methodInvocationRef.putAll(configs);
    }

    public void addImport(String importName) throws EngineImportException
    {
        validateImportAndAdd(importName, imports);
    }

    public void addAnnotationImport(String importName) throws EngineImportException
    {
        validateImportAndAdd(importName, annotationImports);
    }

    public void addAggregation(String functionName, ConfigurationPlugInAggregationFunction aggregationDesc) throws EngineImportException
    {
        validateFunctionName("aggregation function", functionName);
        if(aggregationDesc.getFactoryClassName() == null || !isClassName(aggregationDesc.getFactoryClassName()))
        {
            throw new EngineImportException("Invalid class name for aggregation factory '" + aggregationDesc.getFactoryClassName() + "'");
        }
        aggregationFunctions.put(functionName.toLowerCase(), aggregationDesc);
    }

    public void addSingleRow(String functionName, String singleRowFuncClass, String methodName, ConfigurationPlugInSingleRowFunction.ValueCache valueCache, ConfigurationPlugInSingleRowFunction.FilterOptimizable filterOptimizable, boolean rethrowExceptions) throws EngineImportException {
        validateFunctionName("single-row", functionName);

        if(!isClassName(singleRowFuncClass))
        {
            throw new EngineImportException("Invalid class name for aggregation '" + singleRowFuncClass + "'");
        }
        singleRowFunctions.put(functionName.toLowerCase(), new EngineImportSingleRowDesc(singleRowFuncClass, methodName, valueCache, filterOptimizable, rethrowExceptions));
    }

    public AggregationFunctionFactory resolveAggregationFactory(String name) throws EngineImportUndefinedException, EngineImportException {
        ConfigurationPlugInAggregationFunction desc = aggregationFunctions.get(name);
        if (desc == null)
        {
            desc = aggregationFunctions.get(name.toLowerCase());
        }
        if (desc == null || desc.getFactoryClassName() == null)
        {
            throw new EngineImportUndefinedException("A function named '" + name + "' is not defined");
        }

        String className = desc.getFactoryClassName();
        Class clazz;
        try
        {
            clazz = getClassForNameProvider().classForName(className);
        }
        catch (ClassNotFoundException ex)
        {
            throw new EngineImportException("Could not load aggregation factory class by name '" + className + "'", ex);
        }

        Object object;
        try
        {
            object = clazz.newInstance();
        }
        catch (InstantiationException e)
        {
            throw new EngineImportException("Error instantiating aggregation factory class by name '" + className + "'", e);
        }
        catch (IllegalAccessException e)
        {
            throw new EngineImportException("Illegal access instatiating aggregation factory class by name '" + className + "'", e);
        }

        if (!(object instanceof AggregationFunctionFactory))
        {
            throw new EngineImportException("Aggregation class by name '" + className + "' does not implement AggregationFunctionFactory");
        }
        return (AggregationFunctionFactory) object;
    }

    public void addAggregationMultiFunction(ConfigurationPlugInAggregationMultiFunction desc) throws EngineImportException {
        LinkedHashSet<String> orderedImmutableFunctionNames = new LinkedHashSet<String>();
        for (String functionName : desc.getFunctionNames()) {
            orderedImmutableFunctionNames.add(functionName.toLowerCase());
            validateFunctionName("aggregation multi-function", functionName.toLowerCase());
        }
        if(!isClassName(desc.getMultiFunctionFactoryClassName()))
        {
            throw new EngineImportException("Invalid class name for aggregation multi-function factory '" + desc.getMultiFunctionFactoryClassName() + "'");
        }
        aggregationAccess.add(new Pair<Set<String>, ConfigurationPlugInAggregationMultiFunction>(orderedImmutableFunctionNames, desc));
    }

    public ConfigurationPlugInAggregationMultiFunction resolveAggregationMultiFunction(String name) {
        for (Pair<Set<String>, ConfigurationPlugInAggregationMultiFunction> config : aggregationAccess) {
            if (config.getFirst().contains(name.toLowerCase())) {
                return config.getSecond();
            }
        }
        return null;
    }

    public Pair<Class, EngineImportSingleRowDesc> resolveSingleRow(String name) throws EngineImportException, EngineImportUndefinedException
    {
        EngineImportSingleRowDesc pair = singleRowFunctions.get(name);
        if (pair == null)
        {
            pair = singleRowFunctions.get(name.toLowerCase());
        }
        if (pair == null)
        {
            throw new EngineImportUndefinedException("A function named '" + name + "' is not defined");
        }

        Class clazz;
        try
        {
            clazz = getClassForNameProvider().classForName(pair.getClassName());
        }
        catch (ClassNotFoundException ex)
        {
            throw new EngineImportException("Could not load single-row function class by name '" + pair.getClassName() + "'", ex);
        }
        return new Pair<Class, EngineImportSingleRowDesc>(clazz, pair);
    }

    public Method resolveMethodOverloadChecked(String className, String methodName, Class[] paramTypes, boolean[] allowEventBeanType, boolean[] allowEventBeanCollType)
            throws EngineImportException
    {
        Class clazz;
        try
        {
            clazz = resolveClassInternal(className, false, false);
        }
        catch (ClassNotFoundException e)
        {
            throw new EngineImportException("Could not load class by name '" + className + "', please check imports", e);
        }

        try
        {
            return MethodResolver.resolveMethod(clazz, methodName, paramTypes, false, allowEventBeanType, allowEventBeanCollType);
        }
        catch (EngineNoSuchMethodException e)
        {
            throw convert(clazz, methodName, paramTypes, e, false);
        }
    }

    public Constructor resolveCtor(Class clazz, Class[] paramTypes) throws EngineImportException {
        try
        {
            return MethodResolver.resolveCtor(clazz, paramTypes);
        }
        catch (EngineNoSuchCtorException e)
        {
            throw convert(clazz, paramTypes, e);
        }
    }

    public Method resolveMethodOverloadChecked(String className, String methodName) throws EngineImportException {
        Class clazz;
        try {
            clazz = resolveClassInternal(className, false, false);
        }
        catch (ClassNotFoundException e) {
            throw new EngineImportException("Could not load class by name '" + className + "', please check imports", e);
        }
        return resolveMethodInternalCheckOverloads(clazz, methodName, MethodModifiers.REQUIRE_STATIC_AND_PUBLIC);
    }

    public Method resolveNonStaticMethodOverloadChecked(Class clazz, String methodName) throws EngineImportException {
        return resolveMethodInternalCheckOverloads(clazz, methodName, MethodModifiers.REQUIRE_NONSTATIC_AND_PUBLIC);
    }

    public Class resolveClass(String className, boolean forAnnotation) throws EngineImportException {
        Class clazz;
        try
        {
            clazz = resolveClassInternal(className, false, forAnnotation);
        }
        catch (ClassNotFoundException e)
        {
            throw new EngineImportException("Could not load class by name '" + className + "', please check imports", e);
        }

        return clazz;
    }

    public Class resolveAnnotation(String className) throws EngineImportException {
        Class clazz;
        try
        {
            clazz = resolveClassInternal(className, true, true);
        }
        catch (ClassNotFoundException e)
        {
            throw new EngineImportException("Could not load annotation class by name '" + className + "', please check imports", e);
        }

        return clazz;
    }

    /**
     * Finds a class by class name using the auto-import information provided.
     * @param className is the class name to find
     * @param requireAnnotation whether the class must be an annotation
     * @param forAnnotationUse whether resolving class for use with annotations
     * @return class
     * @throws ClassNotFoundException if the class cannot be loaded
     */
    protected Class resolveClassInternal(String className, boolean requireAnnotation, boolean forAnnotationUse) throws ClassNotFoundException
    {
		// Attempt to retrieve the class with the name as-is
		try
		{
		    return getClassForNameProvider().classForName(className);
		}
		catch(ClassNotFoundException e)
        {
            if (log.isDebugEnabled())
            {
                log.debug("Class not found for resolving from name as-is '" + className + "'");
            }
        }

        // check annotation-specific imports first
        if (forAnnotationUse) {
            Class clazz = checkImports(annotationImports, requireAnnotation, className);
            if (clazz != null) {
                return clazz;
            }
        }

        // check all imports
        Class clazz = checkImports(imports, requireAnnotation, className);
        if (clazz != null) {
            return clazz;
        }

        if (!forAnnotationUse) {
            // try to resolve from method references
            for (String name : methodInvocationRef.keySet())
            {
                if (JavaClassHelper.isSimpleNameFullyQualfied(className, name))
                {
                    try
                    {
                        Class found = getClassForNameProvider().classForName(name);
                        if (!requireAnnotation || found.isAnnotation()) {
                            return found;
                        }
                    }
                    catch (ClassNotFoundException e1)
                    {
                        if (log.isDebugEnabled())
                        {
                            log.debug("Class not found for resolving from method invocation ref:" + name);
                        }
                    }
                }
            }
        }

        // No import worked, the class isn't resolved
		throw new ClassNotFoundException("Unknown class " + className);
	}

    public Method resolveMethod(Class clazz, String methodName, Class[] paramTypes, boolean[] allowEventBeanType, boolean[] allowEventBeanCollType)
			throws EngineImportException
    {
        try
        {
            return MethodResolver.resolveMethod(clazz, methodName, paramTypes, true, allowEventBeanType, allowEventBeanType);
        }
        catch (EngineNoSuchMethodException e)
        {
            throw convert(clazz, methodName, paramTypes, e, true);
        }
    }

    private EngineImportException convert(Class clazz, String methodName, Class[] paramTypes, EngineNoSuchMethodException e, boolean isInstance)
    {
        String expected = JavaClassHelper.getParameterAsString(paramTypes);
        String message = "Could not find ";
        if (!isInstance) {
            message += "static ";
        }
        else {
            message += "enumeration method, date-time method or instance ";
        }

        if (paramTypes.length > 0)
        {
            message += "method named '" + methodName + "' in class '" + JavaClassHelper.getClassNameFullyQualPretty(clazz) + "' with matching parameter number and expected parameter type(s) '" + expected + "'";
        }
        else
        {
            message += "method named '" + methodName + "' in class '" + JavaClassHelper.getClassNameFullyQualPretty(clazz) + "' taking no parameters";
        }

        if (e.getNearestMissMethod() != null)
        {
            message += " (nearest match found was '" + e.getNearestMissMethod().getName();
            if (e.getNearestMissMethod().getParameterTypes().length == 0) {
                message += "' taking no parameters";
            }
            else {
                message += "' taking type(s) '" + JavaClassHelper.getParameterAsString(e.getNearestMissMethod().getParameterTypes()) + "'";
            }
            message += ")";
        }
        return new EngineImportException(message, e);
    }

    private EngineImportException convert(Class clazz, Class[] paramTypes, EngineNoSuchCtorException e)
    {
        String expected = JavaClassHelper.getParameterAsString(paramTypes);
        String message = "Could not find constructor ";
        if (paramTypes.length > 0)
        {
            message += "in class '" + JavaClassHelper.getClassNameFullyQualPretty(clazz) + "' with matching parameter number and expected parameter type(s) '" + expected + "'";
        }
        else
        {
            message += "in class '" + JavaClassHelper.getClassNameFullyQualPretty(clazz) + "' taking no parameters";
        }

        if (e.getNearestMissCtor() != null)
        {
            message += " (nearest matching constructor ";
            if (e.getNearestMissCtor().getParameterTypes().length == 0) {
                message += "taking no parameters";
            }
            else {
                message += "taking type(s) '" + JavaClassHelper.getParameterAsString(e.getNearestMissCtor().getParameterTypes()) + "'";
            }
            message += ")";
        }
        return new EngineImportException(message, e);
    }

    public ExprNode resolveSingleRowExtendedBuiltin(String name) {
        String nameLowerCase = name.toLowerCase();
        if (nameLowerCase.equals("current_evaluation_context")) {
            return new ExprCurrentEvaluationContextNode();
        }
        return null;
    }

    public ExprNode resolveAggExtendedBuiltin(String name, boolean isDistinct) {
        if (!allowExtendedAggregationFunc) {
            return null;
        }
        String nameLowerCase = name.toLowerCase();
        if (nameLowerCase.equals("first")) {
            return new ExprAggMultiFunctionLinearAccessNode(AggregationStateType.FIRST);
        }
        if (nameLowerCase.equals("last")) {
            return new ExprAggMultiFunctionLinearAccessNode(AggregationStateType.LAST);
        }
        if (nameLowerCase.equals("window")) {
            return new ExprAggMultiFunctionLinearAccessNode(AggregationStateType.WINDOW);
        }
        if (nameLowerCase.equals("firstever")) {
            return new ExprFirstEverNode(isDistinct);
        }
        if (nameLowerCase.equals("lastever")) {
            return new ExprLastEverNode(isDistinct);
        }
        if (nameLowerCase.equals("countever")) {
            return new ExprCountEverNode(isDistinct);
        }
        if (nameLowerCase.equals("minever")) {
            return new ExprMinMaxAggrNode(isDistinct, MinMaxTypeEnum.MIN, false, true);
        }
        if (nameLowerCase.equals("maxever")) {
            return new ExprMinMaxAggrNode(isDistinct, MinMaxTypeEnum.MAX, false, true);
        }
        if (nameLowerCase.equals("fminever")) {
            return new ExprMinMaxAggrNode(isDistinct, MinMaxTypeEnum.MIN, true, true);
        }
        if (nameLowerCase.equals("fmaxever")) {
            return new ExprMinMaxAggrNode(isDistinct, MinMaxTypeEnum.MAX, true, true);
        }
        if (nameLowerCase.equals("rate")) {
            return new ExprRateAggNode(isDistinct);
        }
        if (nameLowerCase.equals("nth")) {
            return new ExprNthAggNode(isDistinct);
        }
        if (nameLowerCase.equals("leaving")) {
            return new ExprLeavingAggNode(isDistinct);
        }
        if (nameLowerCase.equals("maxby")) {
            return new ExprAggMultiFunctionSortedMinMaxByNode(true, false, false);
        }
        if (nameLowerCase.equals("maxbyever")) {
            return new ExprAggMultiFunctionSortedMinMaxByNode(true, true, false);
        }
        if (nameLowerCase.equals("minby")) {
            return new ExprAggMultiFunctionSortedMinMaxByNode(false, false, false);
        }
        if (nameLowerCase.equals("minbyever")) {
            return new ExprAggMultiFunctionSortedMinMaxByNode(false, true, false);
        }
        if (nameLowerCase.equals("sorted")) {
            return new ExprAggMultiFunctionSortedMinMaxByNode(false, false, true);
        }
        CountMinSketchAggType cmsType = CountMinSketchAggType.fromNameMayMatch(nameLowerCase);
        if (cmsType != null) {
            return new ExprAggCountMinSketchNode(isDistinct, cmsType);
        }
        return null;
    }

    public MathContext getDefaultMathContext() {
        return optionalDefaultMathContext;
    }

    public TimeZone getTimeZone() {
        return timeZone;
    }

    public TimeAbacus getTimeAbacus() {
        return timeAbacus;
    }

    public ConfigurationEngineDefaults.ThreadingProfile getThreadingProfile() {
        return threadingProfile;
    }

    public boolean isSortUsingCollator() {
        return sortUsingCollator;
    }

    public AggregationFactoryFactory getAggregationFactoryFactory() {
        return aggregationFactoryFactory;
    }

    /**
     * For testing, returns imports.
     * @return returns auto-import list as array
     */
    protected String[] getImports()
	{
		return imports.toArray(new String[imports.size()]);
	}

    private static boolean isFunctionName(String functionName)
    {
        String classNameRegEx = "\\w+";
        return functionName.matches(classNameRegEx);
    }

	private static boolean isClassName(String importName)
	{
		String classNameRegEx = "(\\w+\\.)*\\w+(\\$\\w+)?";
		return importName.matches(classNameRegEx);
	}

	private static boolean isPackageName(String importName)
	{
		String classNameRegEx = "(\\w+\\.)+\\*";
		return importName.matches(classNameRegEx);
	}

	// Strip off the final ".*"
	private static String getPackageName(String importName)
	{
		return importName.substring(0, importName.length() - 2);
	}

    private void validateFunctionName(String functionType, String functionName) throws EngineImportException {
        String functionNameLower = functionName.toLowerCase();
        if (aggregationFunctions.containsKey(functionNameLower))
        {
            throw new EngineImportException("Aggregation function by name '" + functionName + "' is already defined");
        }
        if (singleRowFunctions.containsKey(functionNameLower))
        {
            throw new EngineImportException("Single-row function by name '" + functionName + "' is already defined");
        }
        for (Pair<Set<String>, ConfigurationPlugInAggregationMultiFunction> pairs : aggregationAccess) {
            if (pairs.getFirst().contains(functionNameLower)) {
                throw new EngineImportException("Aggregation multi-function by name '" + functionName + "' is already defined");
            }
        }
        if(!isFunctionName(functionName))
        {
            throw new EngineImportException("Invalid " + functionType + " name '" + functionName + "'");
        }
    }

    private Method resolveMethodInternalCheckOverloads(Class clazz, String methodName, MethodModifiers methodModifiers)
            throws EngineImportException
    {
        Method methods[] = clazz.getMethods();
        Set<Method> overloadeds = null;
        Method methodByName = null;

        // check each method by name, add to overloads when multiple methods for the same name
        for (Method method : methods) {
            if (method.getName().equals(methodName)) {
                int modifiers = method.getModifiers();
                boolean isPublic = Modifier.isPublic(modifiers);
                boolean isStatic = Modifier.isStatic(modifiers);
                if (methodModifiers.acceptsPublicFlag(isPublic) && methodModifiers.acceptsStaticFlag(isStatic)) {
                    if (methodByName != null) {
                        if (overloadeds == null) {
                            overloadeds = new HashSet<>();
                        }
                        overloadeds.add(method);
                    }
                    else {
                        methodByName = method;
                    }
                }
            }
        }
        if (methodByName == null) {
            throw new EngineImportException("Could not find " + methodModifiers.getText() + " method named '" + methodName + "' in class '" + clazz.getName() + "'");
        }
        if (overloadeds == null) {
            return methodByName;
        }

        // determine that all overloads have the same result type
        for (Method overloaded : overloadeds) {
            if (!overloaded.getReturnType().equals(methodByName.getReturnType())) {
                throw new EngineImportException("Method by name '" + methodName + "' is overloaded in class '" + clazz.getName() + "' and overloaded methods do not return the same type");
            }
        }

        return methodByName;
    }

    private Class checkImports(List<String> imports, boolean requireAnnotation, String className) throws ClassNotFoundException {
        // Try all the imports
        for(String importName : imports)
        {
            boolean isClassName = isClassName(importName);
            boolean containsPackage = importName.indexOf('.') != -1;
            String classNameWithDot = "." + className;
            String classNameWithDollar = "$" + className;

            // Import is a class name
            if(isClassName)
            {
                if ((containsPackage && importName.endsWith(classNameWithDot)) ||
                        (containsPackage && importName.endsWith(classNameWithDollar)) ||
                        (!containsPackage && importName.equals(className)) ||
                        (!containsPackage && importName.endsWith(classNameWithDollar))) {
                    return getClassForNameProvider().classForName(importName);
                }

                String prefixedClassName = importName + '$' + className;
                try
                {
                    Class clazz = getClassForNameProvider().classForName(prefixedClassName);
                    if (!requireAnnotation || clazz.isAnnotation()) {
                        return clazz;
                    }
                }
                catch(ClassNotFoundException e){
                    if (log.isDebugEnabled())
                    {
                        log.debug("Class not found for resolving from name '" + prefixedClassName + "'");
                    }
                }
            }
            else
            {
                if (requireAnnotation && importName.equals(Configuration.ANNOTATION_IMPORT)) {
                    Class clazz = BuiltinAnnotation.BUILTIN.get(className.toLowerCase());
                    if (clazz != null) {
                        return clazz;
                    }
                }

                // Import is a package name
                String prefixedClassName = getPackageName(importName) + '.' + className;
                try
                {
                    Class clazz = getClassForNameProvider().classForName(prefixedClassName);
                    if (!requireAnnotation || clazz.isAnnotation()) {
                        return clazz;
                    }
                }
                catch(ClassNotFoundException e){
                    if (log.isDebugEnabled())
                    {
                        log.debug("Class not found for resolving from name '" + prefixedClassName + "'");
                    }
                }
            }
        }

        return null;
    }

    private void validateImportAndAdd(String importName, List<String> imports) throws EngineImportException {
        if(!isClassName(importName) && !isPackageName(importName))
        {
            throw new EngineImportException("Invalid import name '" + importName + "'");
        }
        if (log.isDebugEnabled()) {
            log.debug("Adding import " + importName);
        }

        imports.add(importName);
    }

    private enum MethodModifiers {
        REQUIRE_STATIC_AND_PUBLIC("public static", true),
        REQUIRE_NONSTATIC_AND_PUBLIC("public non-static", false);

        private final String text;
        private final boolean requiredStaticFlag;

        MethodModifiers(String text, boolean requiredStaticFlag) {
            this.text = text;
            this.requiredStaticFlag = requiredStaticFlag;
        }

        public boolean acceptsPublicFlag(boolean isPublic) {
            return isPublic;
        }

        public boolean acceptsStaticFlag(boolean isStatic) {
            return requiredStaticFlag == isStatic;
        }

        public String getText() {
            return text;
        }
    }
}
