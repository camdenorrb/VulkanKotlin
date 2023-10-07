package dev.twelveoclock.vulkan

import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWVulkan
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.EXTDebugUtils.*
import org.lwjgl.vulkan.VK10.*
import java.nio.LongBuffer
import java.util.*
import kotlin.properties.Delegates


class VulkanApp {

	private lateinit var graphicsQueue: VkQueue

	private lateinit var device: VkDevice

	private lateinit var physicalDevice: VkPhysicalDevice

	private var debugMessenger by Delegates.notNull<Long>()

	lateinit var instance: VkInstance
		private set

	var window by Delegates.notNull<Long>()
		private set


	fun start() {
		createWindow()
		initVulkan()
		mainLoop()
		cleanup()
	}

	private fun initVulkan() {
		instance = createInstance()
		setupDebugMessenger()
		physicalDevice = pickPhysicalDevice()
		createLogicalDevice()
	}

	private fun createLogicalDevice() = MemoryStack.stackPush().use { stack ->

		val indices = findQueueFamily(physicalDevice)!!

		val queueCreateInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
		queueCreateInfo.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
		queueCreateInfo.queueFamilyIndex(indices.graphicsFamily)
		queueCreateInfo.pQueuePriorities(stack.floats(1f))

		val deviceFeatures = VkPhysicalDeviceFeatures.calloc(stack)

		val createInfo = VkDeviceCreateInfo.calloc(stack)
		createInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
		createInfo.pQueueCreateInfos(queueCreateInfo)
		createInfo.pEnabledFeatures(deviceFeatures)

		// For older vulkan support, newer versions set this implicitly and ignores this
		if (IS_VALIDATION_ENABLED) {
			createInfo.ppEnabledLayerNames(validationLayersAsPointerBuffer(stack))
		}

		val pDevice = stack.pointers(VK_NULL_HANDLE)
		check(vkCreateDevice(physicalDevice, createInfo, null, pDevice) == VK_SUCCESS) {
			"Failed to create logical device"
		}

		device = VkDevice(pDevice[0], physicalDevice, createInfo)

		val pGraphicsQueue = stack.pointers(VK_NULL_HANDLE)
		vkGetDeviceQueue(device, indices.graphicsFamily, 0, pGraphicsQueue)
		graphicsQueue = VkQueue(pGraphicsQueue[0], device)
	}

	private fun pickPhysicalDevice(): VkPhysicalDevice = MemoryStack.stackPush().use { stack ->

		val deviceCount = stack.ints(0)

		vkEnumeratePhysicalDevices(instance, deviceCount, null)
		check(deviceCount[0] != 0) {
			"Failed to find GPUs with Vulkan support!"
		}

		val ppPhysicalDevices = stack.mallocPointer(deviceCount[0])
		vkEnumeratePhysicalDevices(instance, deviceCount, ppPhysicalDevices)


		val scoredPhysicalDevices = sortedSetOf<ScoredPhysicalDevice>(Collections.reverseOrder())

		for (i in 0..<ppPhysicalDevices.capacity()) {

			val device = VkPhysicalDevice(ppPhysicalDevices.get(i), instance)

			if (!isDeviceSuitable(device)) {
				continue
			}

			val features = VkPhysicalDeviceFeatures.malloc(stack)
			vkGetPhysicalDeviceFeatures(device, features)

			val properties = VkPhysicalDeviceProperties.malloc(stack)
			vkGetPhysicalDeviceProperties(device, properties)

			scoredPhysicalDevices.add(ScoredPhysicalDevice(
				rateDeviceSuitability(properties, features),
				device
			))
		}

		check(scoredPhysicalDevices.isNotEmpty()) {
			"Failed to find suitable GPU"
		}

		return@use scoredPhysicalDevices.first().physicalDevice
	}

	private fun isDeviceSuitable(device: VkPhysicalDevice): Boolean {
		return findQueueFamily(device) != null
	}

	private fun validationLayersAsPointerBuffer(stack: MemoryStack): PointerBuffer {

		val buffer = stack.mallocPointer(VALIDATION_LAYERS.size)
		VALIDATION_LAYERS.map(stack::UTF8).forEach(buffer::put)

		return buffer.rewind()
	}

	private fun findQueueFamily(device: VkPhysicalDevice): QueueFamilyIndices? = MemoryStack.stackPush().use { stack ->

		// Get queue count on first call
		val queueFamilyCount = stack.ints(0)
		vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, null)

		val queueFamilies = VkQueueFamilyProperties.malloc(queueFamilyCount[0], stack)
		vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, queueFamilies)

		val queueFamily = queueFamilies.withIndex().firstOrNull {
			(it.value.queueFlags() and VK_QUEUE_GRAPHICS_BIT) != 0
		}

		return queueFamily?.let { QueueFamilyIndices(it.index) }
	}

	private fun rateDeviceSuitability(
		properties: VkPhysicalDeviceProperties,
		features: VkPhysicalDeviceFeatures,
	): Int {

		var score = 0

		// Discrete GPUs have a significant performance advantage
		if (properties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
			score += 1000
		}

		// Maximum possible size of textures affects graphics quality
		score += properties.limits().maxImageDimension2D()

		// Application can't function without geometry shaders
		if (!features.geometryShader()) {
			return 0
		}

		return score
	}

	private fun setupDebugMessenger() {

		if (!IS_VALIDATION_ENABLED) {
			return
		}

		MemoryStack.stackPush().use { stack ->

			val createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
			populateDebugMessengerCreateInfo(createInfo)

			val pDebugMessenger = stack.longs(VK_NULL_HANDLE)
			check(createDebugUtilsMessengerEXT(instance, createInfo, null, pDebugMessenger) == VK_SUCCESS)

			debugMessenger = pDebugMessenger[0]
		}
	}

	private fun createInstance(): VkInstance {

		if (IS_VALIDATION_ENABLED) {
			checkValidationLayerSupport()
		}

		return MemoryStack.stackPush().use { stack ->

			val appInfo = VkApplicationInfo.calloc(stack)
			appInfo.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
			appInfo.pApplicationName(stack.UTF8Safe("Hello Triangle"))
			appInfo.applicationVersion(VK_MAKE_VERSION(1, 0, 0))
			appInfo.pEngineName(stack.UTF8Safe("No Engine"))
			appInfo.engineVersion(VK_MAKE_VERSION(1, 0, 0))
			appInfo.apiVersion(VK_API_VERSION_1_0)

			val createInfo = VkInstanceCreateInfo.calloc(stack)
			createInfo.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
			createInfo.pApplicationInfo(appInfo)
			createInfo.ppEnabledExtensionNames(getRequiredExtensions(stack))

			if (IS_VALIDATION_ENABLED) {
				createInfo.ppEnabledLayerNames(validationLayersAsPointerBuffer(stack))
			}
			else {
				createInfo.ppEnabledLayerNames(null)
			}

			val instancePointer = stack.mallocPointer(1)
			if (vkCreateInstance(createInfo, null, instancePointer) != VK_SUCCESS) {
				throw RuntimeException("Failed to create instance")
			}

			return@use VkInstance(instancePointer.get(0), createInfo)
		}
	}

	private fun createWindow() {

		if (!glfwInit()) {
			throw RuntimeException("Cannot initialize GLFW");
		}

		glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API) // Init GLFW without OpenGL context
		glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE) // Resizing takes some extra work

		window = glfwCreateWindow(800, 600, "Vulkan", NULL, NULL)
		if (window == NULL) {
			throw RuntimeException("Cannot create window");
		}
	}

	private fun mainLoop() {
		while (!glfwWindowShouldClose(window)) {
			glfwPollEvents()
		}
	}

	private fun cleanup() {

		vkDestroyDevice(device, null)

		if (IS_VALIDATION_ENABLED) {
			destroyDebugUtilsMessengerEXT(instance, debugMessenger, null)
		}

		vkDestroyInstance(instance, null)
		glfwDestroyWindow(window)
		glfwTerminate()
	}

	private fun checkValidationLayerSupport() = MemoryStack.stackPush().use { stack ->

		// Fill in layerCount
		val layerCount = stack.ints(0)
		vkEnumerateInstanceLayerProperties(layerCount, null)

		// Use layerCount to get available layers
		val availableLayers = VkLayerProperties.malloc(layerCount[0], stack)
		vkEnumerateInstanceLayerProperties(layerCount, availableLayers)

		check(availableLayers.map { it.layerNameString() }.containsAll(VALIDATION_LAYERS)) {
			"Available Layers: ${availableLayers.map { it.layerNameString() }}"
		}
	}

	private fun getRequiredExtensions(stack: MemoryStack): PointerBuffer {

		val extensions = GLFWVulkan.glfwGetRequiredInstanceExtensions()!!

		if (IS_VALIDATION_ENABLED) {

			val result = stack.mallocPointer(extensions.capacity() + 1)

			result.put(extensions)
			result.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME))

			return result.rewind()
		}

		return extensions
	}

	private fun populateDebugMessengerCreateInfo(debugCreateInfo: VkDebugUtilsMessengerCreateInfoEXT) {
		debugCreateInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
		debugCreateInfo.messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
		debugCreateInfo.messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
		debugCreateInfo.pfnUserCallback(::debugCallback)
	}


	companion object {

		const val IS_VALIDATION_ENABLED = true

		val VALIDATION_LAYERS by lazy {
			setOf("VK_LAYER_KHRONOS_validation")
		}

		fun debugCallback(messageSeverity: Int, messageType: Int, pCallbackData: Long, pUserData: Long): Int {

			val callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData)

			System.err.println("Validation layer: ${callbackData.pMessageString()}")

			return VK_FALSE
		}

		private fun createDebugUtilsMessengerEXT(
			instance: VkInstance, createInfo: VkDebugUtilsMessengerCreateInfoEXT,
			allocationCallbacks: VkAllocationCallbacks?, pDebugMessenger: LongBuffer,
		): Int {
			return if (vkGetInstanceProcAddr(instance, "vkCreateDebugUtilsMessengerEXT") != NULL) {
				vkCreateDebugUtilsMessengerEXT(instance, createInfo, allocationCallbacks, pDebugMessenger)
			} else VK_ERROR_EXTENSION_NOT_PRESENT
		}

		private fun destroyDebugUtilsMessengerEXT(
			instance: VkInstance,
			debugMessenger: Long,
			allocationCallbacks: VkAllocationCallbacks?,
		) {
			if (vkGetInstanceProcAddr(instance, "vkDestroyDebugUtilsMessengerEXT") != NULL) {
				vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, allocationCallbacks)
			}
		}


		data class QueueFamilyIndices(
			val graphicsFamily: Int,
		)

		data class ScoredPhysicalDevice(
			val score: Int,
			val physicalDevice: VkPhysicalDevice,
		) : Comparable<ScoredPhysicalDevice> {

			override fun compareTo(other: ScoredPhysicalDevice): Int {
				return score.compareTo(other.score)
			}

		}
	}

}
