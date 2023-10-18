package dev.twelveoclock.vulkan

import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWVulkan
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.EXTDebugUtils.*
import org.lwjgl.vulkan.KHRSurface.*
import org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME
import org.lwjgl.vulkan.VK10.*
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.*
import kotlin.collections.HashSet
import kotlin.properties.Delegates


class VulkanApp {

	private var swapChainAdequate = false

	private lateinit var presentQueue: VkQueue

	private lateinit var graphicsQueue: VkQueue

	private lateinit var device: VkDevice

	private var surface by Delegates.notNull<Long>()


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
		createSurface()
		physicalDevice = pickPhysicalDevice()
		createLogicalDevice()
	}

	private fun createLogicalDevice() = MemoryStack.stackPush().use { stack ->

		val indices = findQueueFamilies(physicalDevice)
		val uniqueQueueFamilies = indices.unique()
		val queueCreateInfos = VkDeviceQueueCreateInfo.calloc(uniqueQueueFamilies.size, stack)

		indices.unique().forEachIndexed { index, value ->
			val queueCreateInfo = queueCreateInfos[index]
			queueCreateInfo.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
			queueCreateInfo.queueFamilyIndex(value.toInt())
			queueCreateInfo.pQueuePriorities(stack.floats(1f))
		}

		val deviceFeatures = VkPhysicalDeviceFeatures.calloc(stack)

		val createInfo = VkDeviceCreateInfo.calloc(stack)
		createInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
		createInfo.pQueueCreateInfos(queueCreateInfos)
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

		val pQueue = stack.pointers(VK_NULL_HANDLE)

		vkGetDeviceQueue(device, indices.graphicsFamily!!.toInt(), 0, pQueue)
		graphicsQueue = VkQueue(pQueue[0], device)

		vkGetDeviceQueue(device, indices.presentFamily!!.toInt(), 0, pQueue)
		presentQueue = VkQueue(pQueue[0], device)
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

	private fun createSurface() = MemoryStack.stackPush().use { stack ->

		val pSurface = stack.longs(0)
		check(GLFWVulkan.glfwCreateWindowSurface(instance, window, null, pSurface) == VK_SUCCESS) {
			"Failed to create window surface"
		}

		surface = pSurface[0]
	}

	private fun isDeviceSuitable(device: VkPhysicalDevice): Boolean {

		val extensionsSupported = checkDeviceExtensionSupport(device)

		if (extensionsSupported) {
			MemoryStack.stackPush().use { stack ->

				val swapChainSupport = querySwapChainSupport(device)

				swapChainAdequate = swapChainSupport.formats?.hasRemaining() == true &&
						swapChainSupport.presentModes?.hasRemaining() == true
			}
		}

		return findQueueFamilies(device).isComplete() && extensionsSupported && swapChainAdequate
	}

	private fun chooseSwapSurfaceFormat(availableFormats: VkSurfaceFormatKHR.Buffer): VkSurfaceFormatKHR? {
		return availableFormats.find {
			it.format() == VK_FORMAT_B8G8R8A8_SRGB && it.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
		}
	}


	private fun checkDeviceExtensionSupport(device: VkPhysicalDevice): Boolean = MemoryStack.stackPush().use { stack ->

		val extensionCount = stack.ints(0)
		vkEnumerateDeviceExtensionProperties(device, null as String?, extensionCount, null)

		val availableExtensions = VkExtensionProperties.malloc(extensionCount[0])
		vkEnumerateDeviceExtensionProperties(device, null as String?, extensionCount, availableExtensions)

		return availableExtensions
			.mapTo(HashSet(extensionCount[0]), VkExtensionProperties::extensionNameString)
			.containsAll(REQUIRED_DEVICE_EXTENSIONS)
	}

	private fun validationLayersAsPointerBuffer(stack: MemoryStack): PointerBuffer {

		val buffer = stack.mallocPointer(VALIDATION_LAYERS.size)
		VALIDATION_LAYERS.map(stack::UTF8).forEach(buffer::put)

		return buffer.rewind()
	}

	private fun findQueueFamilies(device: VkPhysicalDevice): QueueFamilyIndices = MemoryStack.stackPush().use { stack ->

		val indices = QueueFamilyIndices()

		// Get queue count on first call
		val queueFamilyCount = stack.ints(0)
		vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, null)

		val queueFamilies = VkQueueFamilyProperties.malloc(queueFamilyCount[0], stack)
		vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, queueFamilies)

		val presentSupport = stack.ints(0)

		for (i in 0..<queueFamilies.capacity()) {

			if (indices.isComplete()) {
				break
			}

			if (queueFamilies[i].queueFlags() and VK_QUEUE_GRAPHICS_BIT != 0) {
				indices.graphicsFamily = i.toUInt()
			}

			vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface, presentSupport)

			if (presentSupport[0] == VK_TRUE) {
				indices.presentFamily = i.toUInt()
			}
		}

		return indices
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

		vkDestroySurfaceKHR(instance, surface, null)
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

	fun querySwapChainSupport(device: VkPhysicalDevice): SwapChainSupportDetails = MemoryStack.stackPush().use { stack ->

		val capabilities = VkSurfaceCapabilitiesKHR.malloc(stack)
		vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, surface, capabilities)

		val details = SwapChainSupportDetails(capabilities)

		val count = stack.ints(0)

		// Format count
		vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, null)

		if (count[0] != 0) {
			details.formats = VkSurfaceFormatKHR.malloc(count[0], stack)
			vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, details.formats)
		}

		// Present mode count
		vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, null)
		if (count[0] != 0) {
			details.presentModes = stack.mallocInt(count[0])
			vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, details.presentModes)
		}

		return@use details
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

		val REQUIRED_DEVICE_EXTENSIONS = setOf(
			VK_KHR_SWAPCHAIN_EXTENSION_NAME
		)

		fun debugCallback(messageSeverity: Int, messageType: Int, pCallbackData: Long, pUserData: Long): Int {

			val callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData)

			System.err.println("Validation layer: ${callbackData.pMessageString()}")

			return VK_FALSE
		}

		private fun createDebugUtilsMessengerEXT(
			instance: VkInstance,
			createInfo: VkDebugUtilsMessengerCreateInfoEXT,
			allocationCallbacks: VkAllocationCallbacks?,
			pDebugMessenger: LongBuffer,
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

		data class SwapChainSupportDetails(
			val capabilities: VkSurfaceCapabilitiesKHR,
			var formats: VkSurfaceFormatKHR.Buffer? = null,
			var presentModes: IntBuffer? = null,
		)

		data class QueueFamilyIndices(
			var graphicsFamily: UInt? = null,
			var presentFamily: UInt? = null,
		) {

			fun isComplete(): Boolean {
				return graphicsFamily != null && presentFamily != null
			}

			fun unique(): List<UInt> {
				return uintArrayOf(graphicsFamily!!, presentFamily!!).distinct()
			}
		}

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
