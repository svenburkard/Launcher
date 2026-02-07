package org.fossify.home.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.os.Process
import android.os.UserManager
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.OnScrollListener
import com.google.android.material.button.MaterialButton
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.toast
import org.fossify.commons.views.MyGridLayoutManager
import org.fossify.home.activities.MainActivity
import org.fossify.home.adapters.LaunchersAdapter
import org.fossify.home.databinding.AllAppsFragmentBinding
import org.fossify.home.extensions.config
import org.fossify.home.extensions.launchApp
import org.fossify.home.extensions.setupDrawerBackground
import org.fossify.home.helpers.ITEM_TYPE_ICON
import org.fossify.home.helpers.UNKNOWN_USER_SERIAL
import org.fossify.home.interfaces.AllAppsListener
import org.fossify.home.models.AppLauncher
import org.fossify.home.models.HomeScreenGridItem

class AllAppsFragment(
    context: Context,
    attributeSet: AttributeSet
) : MyFragment<AllAppsFragmentBinding>(context, attributeSet), AllAppsListener {
    companion object {
        private const val TAG = "AllAppsProfileTabs"
        private const val LOG_PROFILE_TABS = true
        private const val UNSELECTED_TAB_TEXT_ALPHA = 0.8f
        private const val PROFILE_TAB_TEXT_SIZE_SP = 14f
        private const val PROFILE_TAB_HORIZONTAL_PADDING_DP = 16
        private const val PROFILE_TAB_VERTICAL_PADDING_DP = 10
        private const val PROFILE_TAB_HORIZONTAL_MARGIN_DP = 4
        private const val PROFILE_TAB_STROKE_WIDTH_DP = 1
        private const val PROFILE_TAB_CORNER_RADIUS_DP = 18
        private const val PROFILE_TAB_BADGE_SIZE_DP = 12
        private const val PROFILE_TAB_BADGE_INSET_DP = 8
        private const val SELECTED_TAB_BACKGROUND_ALPHA = 64
        private const val UNSELECTED_TAB_STROKE_ALPHA = 180
        private const val MAX_COLOR_ALPHA = 255
        private const val PAUSED_TAB_ALPHA = 0.45f
        private const val ALL_PROFILES_TITLE = "All"
        private const val PROFILE_TITLE_PREFIX = "Profile "
    }

    private var lastTouchCoords = Pair(0f, 0f)
    var touchDownY = -1
    var ignoreTouches = false

    private var launchers = emptyList<AppLauncher>()
    private var selectedUserSerial: Long? = context.config.drawerSelectedProfileSerial
        .takeIf { it != UNKNOWN_USER_SERIAL }

    @SuppressLint("ClickableViewAccessibility")
    override fun setupFragment(activity: MainActivity) {
        this.activity = activity
        this.binding = AllAppsFragmentBinding.bind(this)

        binding.allAppsGrid.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchDownY = -1
            }

            return@setOnTouchListener false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setupDrawerBackground()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun onResume() {
        if (binding.allAppsGrid.layoutManager == null || binding.allAppsGrid.adapter == null) {
            return
        }

        val layoutManager = binding.allAppsGrid.layoutManager as MyGridLayoutManager
        if (layoutManager.spanCount != context.config.drawerColumnCount) {
            onConfigurationChanged()
            // Force redraw due to changed item size
            (binding.allAppsGrid.adapter as LaunchersAdapter).notifyDataSetChanged()
        }
    }

    fun onConfigurationChanged() {
        binding.allAppsGrid.scrollToPosition(0)
        binding.allAppsFastscroller.resetManualScrolling()
        setupViews()

        val layoutManager = binding.allAppsGrid.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = context.config.drawerColumnCount
        setupAdapter(getFilteredLaunchers())
    }

    override fun onInterceptTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) {
            return super.onInterceptTouchEvent(event)
        }

        var shouldIntercept = false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownY = event.y.toInt()
            }

            MotionEvent.ACTION_MOVE -> {
                if (ignoreTouches) {
                    // some devices ACTION_MOVE keeps triggering for the whole long press duration, but we are interested in real moves only, when coords change
                    if (lastTouchCoords.first != event.x || lastTouchCoords.second != event.y) {
                        touchDownY = -1
                        return true
                    }
                }

                // pull the whole fragment down if it is scrolled way to the top and the user pulls it even further
                if (touchDownY != -1) {
                    val distance = event.y.toInt() - touchDownY
                    shouldIntercept =
                        distance > 0 && binding.allAppsGrid.computeVerticalScrollOffset() == 0
                    if (shouldIntercept) {
                        // Hiding is expensive, only do it if focused
                        if (binding.searchBar.hasFocus()) {
                            activity?.hideKeyboard()
                        }
                        activity?.startHandlingTouches(touchDownY)
                        touchDownY = -1
                    }
                }
            }
        }

        lastTouchCoords = Pair(event.x, event.y)
        return shouldIntercept
    }

    fun gotLaunchers(appLaunchers: List<AppLauncher>) {
        launchers = appLaunchers.sortedWith(
            compareBy(
                { it.title.normalizeString().lowercase() },
                { it.packageName },
                { it.userSerial }
            )
        )

        activity?.runOnUiThread {
            updateProfileTabs()
            setupAdapter(getFilteredLaunchers())
        }
    }

    private fun getAdapter() = binding.allAppsGrid.adapter as? LaunchersAdapter

    private fun setupAdapter(launchers: List<AppLauncher>) {
        activity?.runOnUiThread {
            val layoutManager = binding.allAppsGrid.layoutManager as MyGridLayoutManager
            layoutManager.spanCount = context.config.drawerColumnCount

            if (getAdapter() == null) {
                LaunchersAdapter(activity!!, this) {
                    activity?.launchApp(
                        (it as AppLauncher).packageName,
                        it.activityName,
                        it.userSerial
                    )
                    if (activity?.config?.closeAppDrawer == true) {
                        activity?.closeAppDrawer(delayed = true)
                    }
                    ignoreTouches = false
                    touchDownY = -1
                }.apply {
                    binding.allAppsGrid.itemAnimator = null
                    binding.allAppsGrid.adapter = this
                }
            }

            submitList(launchers.toMutableList())
        }
    }

    fun onIconHidden(item: HomeScreenGridItem) {
        val itemToRemove = launchers.firstOrNull {
            it.getLauncherIdentifier() == item.getItemIdentifier()
        }

        if (itemToRemove != null) {
            val position = launchers.indexOfFirst {
                it.getLauncherIdentifier() == item.getItemIdentifier()
            }

            launchers = launchers.toMutableList().apply {
                removeAt(position)
            }

            updateProfileTabs()
            submitList(getFilteredLaunchers())
        }
    }

    fun setupViews() {
        if (activity == null) {
            return
        }

        binding.allAppsFastscroller.updateColors(context.getProperPrimaryColor())
        binding.allAppsGrid.addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // Hiding is expensive, only do it if focused
                if (binding.searchBar.hasFocus() && dy > 0 && binding.allAppsGrid.computeVerticalScrollOffset() > 0) {
                    activity?.hideKeyboard()
                }
            }
        })

        setupDrawerBackground()
        getAdapter()?.updateTextColor(context.getProperTextColor())
        updateProfileTabs()

        binding.searchBar.beVisibleIf(context.config.showSearchBar)
        binding.searchBar.requireToolbar().beGone()
        binding.searchBar.updateColors()
        binding.searchBar.setupMenu()

        binding.searchBar.onSearchTextChangedListener = {
            submitList(getFilteredLaunchers())
        }

        binding.searchBar.binding.topToolbarSearch.setOnEditorActionListener { _, actionId, _ ->
            if (binding.searchBar.getCurrentQuery().isEmpty()) return@setOnEditorActionListener false
            when (actionId) {
                EditorInfo.IME_ACTION_DONE,
                EditorInfo.IME_ACTION_SEARCH,
                EditorInfo.IME_ACTION_GO -> getAdapter()?.launchFirstApp() == true
                else -> false
            }
        }
    }

    private fun showNoResultsPlaceholderIfNeeded() {
        val itemCount = getAdapter()?.itemCount
        binding.noResultsPlaceholder.beVisibleIf(itemCount != null && itemCount == 0)
    }

    override fun onAppLauncherLongPressed(x: Float, y: Float, appLauncher: AppLauncher) {
        val gridItem = HomeScreenGridItem(
            id = null,
            left = -1,
            top = -1,
            right = -1,
            bottom = -1,
            page = 0,
            packageName = appLauncher.packageName,
            activityName = appLauncher.activityName,
            userSerial = appLauncher.userSerial,
            title = appLauncher.title,
            type = ITEM_TYPE_ICON,
            className = "",
            widgetId = -1,
            shortcutId = "",
            icon = null,
            docked = false,
            parentId = null,
            drawable = appLauncher.drawable
        )

        activity?.showHomeIconMenu(x, y, gridItem, true)
        ignoreTouches = true

        binding.searchBar.closeSearch()
    }

    fun onBackPressed(): Boolean {
        if (binding.searchBar.isSearchOpen) {
            binding.searchBar.closeSearch()
            return true
        }

        return false
    }

    private fun submitList(items: List<AppLauncher>) {
        val searchQuery = binding.searchBar.getCurrentQuery()
        val filtered = if (searchQuery.isNotEmpty()) {
            items.filter {
                it.title.normalizeString()
                    .contains(searchQuery.normalizeString(), ignoreCase = true)
            }
        } else {
            items
        }

        getAdapter()?.submitList(filtered) {
            showNoResultsPlaceholderIfNeeded()
        }
    }

    private fun getFilteredLaunchers(): List<AppLauncher> {
        val pausedSerials = getPausedProfileSerials()
        val activeLaunchers = launchers.filter { it.userSerial !in pausedSerials }
        val userSerial = selectedUserSerial ?: return activeLaunchers
        return activeLaunchers.filter { it.userSerial == userSerial }
    }

    private fun updateProfileTabs() {
        val availableUserSerials = getUserProfileSerials().sorted()
        val shouldShowTabs = availableUserSerials.size > 1
        logTabs(
            "profiles=$profileSerials launchers=$launcherSerials " +
                "available=$availableUserSerials showTabs=$shouldShowTabs " +
                "selected=$selectedUserSerial"
        )
        binding.profileTabsScroll.beVisibleIf(shouldShowTabs)
        binding.profileTabsContainer.removeAllViews()

        if (!shouldShowTabs) {
            updateSelectedUserSerial(null)
            return
        }

        val myUserSerial = getMyUserSerial()
        val pausedProfileSerials = getPausedProfileSerials()
        val orderedUserSerials = availableUserSerials.sorted().toMutableList()
        if (myUserSerial != null && orderedUserSerials.remove(myUserSerial)) {
            orderedUserSerials.add(0, myUserSerial)
        }

        if (selectedUserSerial != null && selectedUserSerial !in orderedUserSerials) {
            updateSelectedUserSerial(null)
        }

        val profileTitles = orderedUserSerials
            .mapIndexed { index, serial -> serial to "$PROFILE_TITLE_PREFIX${index + 1}" }
            .toMap()

        val allTab = createProfileTabView(
            title = ALL_PROFILES_TITLE,
            isSelected = selectedUserSerial == null,
            click = {
                updateSelectedUserSerial(null)
                updateProfileTabs()
                submitList(getFilteredLaunchers())
            }
        )
        binding.profileTabsContainer.addView(allTab)

        orderedUserSerials.forEach { userSerial ->
            val title = profileTitles.getValue(userSerial)
            val isPaused = userSerial in pausedProfileSerials

            val tabView = createProfileTabView(
                title = title,
                isSelected = userSerial == selectedUserSerial,
                isPaused = isPaused,
                click = {
                    updateSelectedUserSerial(userSerial)
                    updateProfileTabs()
                    submitList(getFilteredLaunchers())
                },
                onLongClick = {
                    if (userSerial != myUserSerial) {
                        toggleProfilePause(userSerial)
                    }
                }
            )

            binding.profileTabsContainer.addView(tabView)
        }
    }

    private fun getUserProfileSerials(): List<Long> {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return emptyList()
        val serials = userManager.userProfiles.mapNotNull { handle ->
            val serial = runCatching { userManager.getSerialNumberForUser(handle) }.getOrNull()
            serial?.takeIf { it != UNKNOWN_USER_SERIAL }
        }.distinct()
        logTabs("getUserProfileSerials -> $serials")
        return serials
    }

    private fun getMyUserSerial(): Long? {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return null
        val serial = runCatching {
            userManager.getSerialNumberForUser(Process.myUserHandle())
        }.getOrNull() ?: return null
        return serial.takeIf { it != UNKNOWN_USER_SERIAL }
    }

    private fun getPausedProfileSerials(): Set<Long> {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return emptySet()
        return userManager.userProfiles.mapNotNull { handle ->
            val serial = runCatching { userManager.getSerialNumberForUser(handle) }.getOrNull()
                ?.takeIf { it != UNKNOWN_USER_SERIAL } ?: return@mapNotNull null
            val isPaused = runCatching { userManager.isQuietModeEnabled(handle) }.getOrDefault(false)
            if (isPaused) serial else null
        }.toSet()
    }

    private fun toggleProfilePause(userSerial: Long) {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return
        val userHandle = userManager.getUserForSerialNumber(userSerial) ?: return

        val currentlyPaused = runCatching { userManager.isQuietModeEnabled(userHandle) }.getOrDefault(false)
        val targetPaused = !currentlyPaused
        val changed = runCatching {
            userManager.requestQuietModeEnabled(targetPaused, userHandle)
        }.getOrDefault(false)

        logTabs("toggleProfilePause serial=$userSerial paused=$targetPaused changed=$changed")
        if (!changed) {
            context.toast("Could not change profile state")
        }

        updateProfileTabs()
        submitList(getFilteredLaunchers())
    }

    private fun createProfileTabView(
        title: String,
        isSelected: Boolean,
        isPaused: Boolean = false,
        click: () -> Unit,
        onLongClick: (() -> Unit)? = null,
    ): View {
        val horizontalPadding = PROFILE_TAB_HORIZONTAL_PADDING_DP.dpToPx()
        val verticalPadding = PROFILE_TAB_VERTICAL_PADDING_DP.dpToPx()
        val horizontalMargin = PROFILE_TAB_HORIZONTAL_MARGIN_DP.dpToPx()
        val badgeSizePx = PROFILE_TAB_BADGE_SIZE_DP.dpToPx()
        val badgeInsetPx = PROFILE_TAB_BADGE_INSET_DP.dpToPx()

        val primaryColor = context.getProperPrimaryColor()
        val textColor = context.getProperTextColor()
        val button = MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, PROFILE_TAB_TEXT_SIZE_SP)
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            layoutParams = FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            strokeWidth = PROFILE_TAB_STROKE_WIDTH_DP.dpToPx()
            cornerRadius = PROFILE_TAB_CORNER_RADIUS_DP.dpToPx()
            if (isSelected) {
                setTextColor(primaryColor)
                strokeColor = android.content.res.ColorStateList.valueOf(primaryColor)
                setBackgroundColor(primaryColor.adjustAlpha(SELECTED_TAB_BACKGROUND_ALPHA))
                alpha = 1f
            } else {
                setTextColor(textColor)
                strokeColor = android.content.res.ColorStateList.valueOf(
                    textColor.adjustAlpha(UNSELECTED_TAB_STROKE_ALPHA)
                )
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                alpha = UNSELECTED_TAB_TEXT_ALPHA
            }

            if (isPaused) {
                alpha = if (isSelected) PAUSED_TAB_ALPHA else minOf(alpha, PAUSED_TAB_ALPHA)
            }
            setOnClickListener { click() }
            if (onLongClick != null) {
                setOnLongClickListener {
                    onLongClick()
                    true
                }
            }
        }

        val badgeView = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_media_pause)
            imageTintList = android.content.res.ColorStateList.valueOf(primaryColor)
            visibility = if (isPaused) View.VISIBLE else View.GONE
            layoutParams = FrameLayout.LayoutParams(badgeSizePx, badgeSizePx, Gravity.END or Gravity.TOP).apply {
                marginEnd = badgeInsetPx
                topMargin = badgeInsetPx
            }
            isClickable = false
            isFocusable = false
        }

        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = horizontalMargin
                rightMargin = horizontalMargin
            }
            addView(button)
            addView(badgeView)
        }
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    private fun Int.adjustAlpha(alpha: Int): Int {
        return android.graphics.Color.argb(
            alpha.coerceIn(0, MAX_COLOR_ALPHA),
            android.graphics.Color.red(this),
            android.graphics.Color.green(this),
            android.graphics.Color.blue(this)
        )
    }

    private fun updateSelectedUserSerial(userSerial: Long?) {
        selectedUserSerial = userSerial
        context.config.drawerSelectedProfileSerial = userSerial ?: UNKNOWN_USER_SERIAL
    }

    private fun logTabs(message: String) {
        if (LOG_PROFILE_TABS) {
            Log.d(TAG, message)
        }
    }
}
