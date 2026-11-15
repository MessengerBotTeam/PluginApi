/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import java.io.InputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URL

private fun unwrap(error: InvocationTargetException): Throwable = error.targetException ?: error

// A static member is like naming its class, so it follows the lookup rules. An instance member acts on an object the
// script already holds, so it follows the value rules: a public method of sun.nio.ch.FileChannelImpl is a FileChannel method.
private fun check(
    type: Class<*>,
    what: String,
    static: Boolean = false,
) {
    val name = GuardPolicy.originalOf(type).name
    val rules = GuardContext.rules()
    if (if (static) rules.isDenied(name) else rules.isDeniedValue(name)) {
        GuardContext.reportClass(name)
        throw SecurityException("$what on $name is not available to this project")
    }
}

private fun realTypes(list: Array<out GuardClass>): Array<Class<*>> = Array(list.size) { list[it].real }

/**
 * `java.lang.Class` for scripts. It describes the class that was asked for ([getName] is `java.io.File`, not the
 * guard's), while constructors and static methods come from the guard's replacement, so what a script makes through
 * reflection is guarded as well. Members of classes the project may not use are refused when used.
 */
class GuardClass internal constructor(
    internal val real: Class<*>,
    internal val impl: Class<*>,
) {
    fun getName(): String = real.name

    fun getSimpleName(): String = real.simpleName

    fun getCanonicalName(): String? = real.canonicalName

    fun getTypeName(): String = real.typeName

    fun getPackageName(): String = real.`package`?.name ?: real.name.substringBeforeLast('.', "")

    fun isInstance(value: Any?): Boolean = real.isInstance(value)

    fun isAssignableFrom(other: GuardClass): Boolean = real.isAssignableFrom(other.real)

    fun isInterface() = real.isInterface

    fun isArray() = real.isArray

    fun isPrimitive() = real.isPrimitive

    fun isEnum() = real.isEnum

    fun isAnnotation() = real.isAnnotation

    fun isAnonymousClass() = real.isAnonymousClass

    fun isLocalClass() = real.isLocalClass

    fun isMemberClass() = real.isMemberClass

    fun isSynthetic() = real.isSynthetic

    fun getModifiers() = real.modifiers

    fun getSuperclass(): GuardClass? = real.superclass?.let { of(it) }

    fun getInterfaces(): Array<GuardClass> = real.interfaces.map { of(it) }.toTypedArray()

    fun getComponentType(): GuardClass? = real.componentType?.let { of(it) }

    fun arrayType(): GuardClass = of(java.lang.reflect.Array.newInstance(real, 0).javaClass)

    fun getEnclosingClass(): GuardClass? = real.enclosingClass?.let { of(it) }

    fun getDeclaringClass(): GuardClass? = real.declaringClass?.let { of(it) }

    fun getClassLoader(): GuardClassLoader = GuardClassLoader.INSTANCE

    fun getEnumConstants(): Array<Any?>? = real.enumConstants?.map { it }?.toTypedArray()

    fun desiredAssertionStatus() = false

    fun cast(value: Any?): Any? = real.cast(value)

    fun getResourceAsStream(name: String): InputStream? = real.getResourceAsStream(name)

    fun getResource(name: String): URL? = real.getResource(name)

    private fun usable(member: Class<*>) = !GuardContext.rules().isDeniedValue(GuardPolicy.originalOf(member).name)

    fun getMethods(): Array<GuardMethod> = impl.methods.filter { usable(it.declaringClass) }.map { GuardMethod(it) }.toTypedArray()

    fun getDeclaredMethods(): Array<GuardMethod> = impl.declaredMethods.filter { Modifier.isPublic(it.modifiers) || impl == real }.map { GuardMethod(it) }.toTypedArray()

    @Throws(NoSuchMethodException::class)
    fun getMethod(
        name: String,
        vararg parameters: GuardClass,
    ) = GuardMethod(impl.getMethod(name, *realTypes(parameters)))

    @Throws(NoSuchMethodException::class)
    fun getDeclaredMethod(
        name: String,
        vararg parameters: GuardClass,
    ) = GuardMethod(impl.getDeclaredMethod(name, *realTypes(parameters)))

    fun getConstructors(): Array<GuardConstructor> = impl.constructors.map { GuardConstructor(it) }.toTypedArray()

    fun getDeclaredConstructors(): Array<GuardConstructor> = impl.declaredConstructors.filter { Modifier.isPublic(it.modifiers) || impl == real }.map { GuardConstructor(it) }.toTypedArray()

    @Throws(NoSuchMethodException::class)
    fun getConstructor(vararg parameters: GuardClass) = GuardConstructor(impl.getConstructor(*realTypes(parameters)))

    @Throws(NoSuchMethodException::class)
    fun getDeclaredConstructor(vararg parameters: GuardClass) = GuardConstructor(impl.getDeclaredConstructor(*realTypes(parameters)))

    fun getFields(): Array<GuardField> = impl.fields.map { GuardField(it) }.toTypedArray()

    fun getDeclaredFields(): Array<GuardField> = impl.declaredFields.filter { Modifier.isPublic(it.modifiers) || impl == real }.map { GuardField(it) }.toTypedArray()

    @Throws(NoSuchFieldException::class)
    fun getField(name: String) = GuardField(impl.getField(name))

    @Throws(NoSuchFieldException::class)
    fun getDeclaredField(name: String) = GuardField(impl.getDeclaredField(name))

    /** `Class.newInstance()`, through the guard's no-argument constructor. */
    fun newInstance(): Any? = getConstructor().newInstance()

    override fun toString() = (if (real.isInterface) "interface " else if (real.isPrimitive) "" else "class ") + real.name

    override fun equals(other: Any?) = other is GuardClass && other.real == real

    override fun hashCode() = real.hashCode()

    companion object {
        /** The stand-in for [type], which may be a real class or one of the guard's replacements. */
        @JvmStatic
        fun of(type: Class<*>): GuardClass {
            val original = GuardPolicy.originalOf(type)
            return GuardClass(original, GuardPolicy.substituteOf(original.name) ?: original)
        }

        @JvmStatic
        @Throws(ClassNotFoundException::class)
        fun forName(name: String): GuardClass {
            GuardPolicy.substituteOf(name)?.let { return GuardClass(Class.forName(name), it) }
            if (GuardPolicy.lookup(name) == GuardPolicy.Lookup.Denied) {
                throw ClassNotFoundException(name)
            }
            return of(Class.forName(name))
        }

        @JvmStatic
        @Throws(ClassNotFoundException::class)
        fun forName(
            name: String,
            initialize: Boolean,
            loader: GuardClassLoader?,
        ): GuardClass = forName(name)
    }
}

/** `java.lang.ClassLoader` for scripts: it finds classes by name under the same rules as [GuardClass.forName]. */
class GuardClassLoader private constructor() {
    @Throws(ClassNotFoundException::class)
    fun loadClass(name: String): GuardClass = GuardClass.forName(name)

    fun getParent(): GuardClassLoader? = null

    fun getResourceAsStream(name: String): InputStream? = GuardClassLoader::class.java.classLoader?.getResourceAsStream(name)

    fun getResource(name: String): URL? = GuardClassLoader::class.java.classLoader?.getResource(name)

    override fun toString() = "GuardClassLoader"

    companion object {
        internal val INSTANCE = GuardClassLoader()

        @JvmStatic
        fun getSystemClassLoader() = INSTANCE
    }
}

class GuardMethod internal constructor(private val method: Method) {
    fun getName(): String = method.name

    fun getModifiers() = method.modifiers

    fun getParameterCount() = method.parameterCount

    fun getParameterTypes(): Array<GuardClass> = method.parameterTypes.map { GuardClass.of(it) }.toTypedArray()

    fun getReturnType(): GuardClass = GuardClass.of(method.returnType)

    fun getExceptionTypes(): Array<GuardClass> = method.exceptionTypes.map { GuardClass.of(it) }.toTypedArray()

    fun getDeclaringClass(): GuardClass = GuardClass.of(method.declaringClass)

    fun isVarArgs() = method.isVarArgs

    fun isDefault() = method.isDefault

    fun isAccessible() = true

    fun setAccessible(flag: Boolean) = Unit

    fun trySetAccessible() = true

    fun invoke(
        target: Any?,
        vararg args: Any?,
    ): Any? {
        check(method.declaringClass, "Calling ${method.name}", Modifier.isStatic(method.modifiers))
        if (target != null) check(target.javaClass, "Calling ${method.name}")
        try {
            return GuardPolicy.replace(method.invoke(target, *args))
        } catch (e: IllegalAccessException) {
            throw SecurityException("${method.declaringClass.name}.${method.name} cannot be called from a script")
        } catch (e: InvocationTargetException) {
            throw unwrap(e)
        }
    }

    override fun toString() = method.toGenericString()

    override fun equals(other: Any?) = other is GuardMethod && other.method == method

    override fun hashCode() = method.hashCode()
}

class GuardConstructor internal constructor(private val constructor: Constructor<*>) {
    fun getName(): String = GuardPolicy.originalOf(constructor.declaringClass).name

    fun getModifiers() = constructor.modifiers

    fun getParameterCount() = constructor.parameterCount

    fun getParameterTypes(): Array<GuardClass> = constructor.parameterTypes.map { GuardClass.of(it) }.toTypedArray()

    fun getExceptionTypes(): Array<GuardClass> = constructor.exceptionTypes.map { GuardClass.of(it) }.toTypedArray()

    fun getDeclaringClass(): GuardClass = GuardClass.of(constructor.declaringClass)

    fun isVarArgs() = constructor.isVarArgs

    fun isAccessible() = true

    fun setAccessible(flag: Boolean) = Unit

    fun trySetAccessible() = true

    fun newInstance(vararg args: Any?): Any? {
        check(constructor.declaringClass, "Creating", static = true)
        try {
            return constructor.newInstance(*args)
        } catch (e: IllegalAccessException) {
            throw SecurityException("${constructor.declaringClass.name} cannot be created from a script")
        } catch (e: InvocationTargetException) {
            throw unwrap(e)
        }
    }

    override fun toString() = constructor.toGenericString()

    override fun equals(other: Any?) = other is GuardConstructor && other.constructor == constructor

    override fun hashCode() = constructor.hashCode()
}

class GuardField internal constructor(private val field: Field) {
    fun getName(): String = field.name

    fun getModifiers() = field.modifiers

    fun getType(): GuardClass = GuardClass.of(field.type)

    fun getDeclaringClass(): GuardClass = GuardClass.of(field.declaringClass)

    fun isEnumConstant() = field.isEnumConstant

    fun isAccessible() = true

    fun setAccessible(flag: Boolean) = Unit

    fun trySetAccessible() = true

    fun get(target: Any?): Any? {
        check(field.declaringClass, "Reading ${field.name}", Modifier.isStatic(field.modifiers))
        if (target != null) check(target.javaClass, "Reading ${field.name}")
        return try {
            GuardPolicy.replace(field.get(target))
        } catch (e: IllegalAccessException) {
            throw SecurityException("${field.declaringClass.name}.${field.name} cannot be read from a script")
        }
    }

    fun set(
        target: Any?,
        value: Any?,
    ) {
        check(field.declaringClass, "Writing ${field.name}", Modifier.isStatic(field.modifiers))
        if (target != null) check(target.javaClass, "Writing ${field.name}")
        try {
            field.set(target, value)
        } catch (e: IllegalAccessException) {
            throw SecurityException("${field.declaringClass.name}.${field.name} cannot be written from a script")
        }
    }

    override fun toString() = field.toGenericString()

    override fun equals(other: Any?) = other is GuardField && other.field == field

    override fun hashCode() = field.hashCode()
}
