package ani.dantotsu.media

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.text.color
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import ani.dantotsu.CustomBottomNavBar
import ani.dantotsu.GesturesListener
import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.ZoomOutPageTransformer
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.media.anime.AnimeWatchFragment
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivityMediaBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadData
import ani.dantotsu.loadImage
import ani.dantotsu.media.manga.MangaReadFragment
import ani.dantotsu.navBarHeight
import ani.dantotsu.media.novel.NovelReadFragment
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.others.ImageViewDialog
import ani.dantotsu.others.getSerialized
import ani.dantotsu.saveData
import ani.dantotsu.settings.UserInterfaceSettings
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import com.flaviofaria.kenburnsview.RandomTransitionGenerator
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.navigation.NavigationBarView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs


class MediaDetailsActivity : AppCompatActivity(), AppBarLayout.OnOffsetChangedListener {

    private lateinit var binding: ActivityMediaBinding
    private val scope = lifecycleScope
    private val model: MediaDetailsViewModel by viewModels()
    private lateinit var tabLayout: NavigationBarView
    private lateinit var uiSettings: UserInterfaceSettings
    var selected = 0
    var anime = true
    private var adult = false

    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        screenWidth = resources.displayMetrics.widthPixels.toFloat()

        //Ui init

        initActivity(this)
        uiSettings = loadData<UserInterfaceSettings>("ui_settings") ?: UserInterfaceSettings()
        if (!uiSettings.immersiveMode) this.window.statusBarColor = ContextCompat.getColor(this, R.color.nav_bg_inv)

        binding.mediaBanner.updateLayoutParams { height += statusBarHeight }
        binding.mediaBannerNoKen.updateLayoutParams { height += statusBarHeight }
        binding.mediaClose.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin += statusBarHeight }
        binding.mediaCollapsing.minimumHeight = statusBarHeight

        if (binding.mediaTab is CustomBottomNavBar) binding.mediaTab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }

        binding.mediaTitle.isSelected = true

        mMaxScrollSize = binding.mediaAppBar.totalScrollRange
        binding.mediaAppBar.addOnOffsetChangedListener(this)

        binding.mediaClose.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        if (uiSettings.bannerAnimations) {
            val adi = AccelerateDecelerateInterpolator()
            val generator = RandomTransitionGenerator((10000 + 15000 * (uiSettings.animationSpeed)).toLong(), adi)
            binding.mediaBanner.setTransitionGenerator(generator)
        }
        val banner = if (uiSettings.bannerAnimations) binding.mediaBanner else binding.mediaBannerNoKen
        val viewPager = binding.mediaViewPager
        tabLayout = binding.mediaTab as NavigationBarView
        viewPager.isUserInputEnabled = false
        viewPager.setPageTransformer(ZoomOutPageTransformer(uiSettings))

        var media: Media = intent.getSerialized("media") ?: return
        media.selected = model.loadSelected(media)

        binding.mediaCoverImage.loadImage(media.cover)
        binding.mediaCoverImage.setOnLongClickListener {
            ImageViewDialog.newInstance(
                this,
                media.userPreferredName + "[Cover]",
                media.cover
            )
        }
        banner.loadImage(media.banner ?: media.cover, 400)
        val gestureDetector = GestureDetector(this, object : GesturesListener() {
            override fun onDoubleClick(event: MotionEvent) {
                if (!uiSettings.bannerAnimations)
                    snackString(getString(R.string.enable_banner_animations))
                else {
                    binding.mediaBanner.restart()
                    binding.mediaBanner.performClick()
                }
            }

            override fun onLongClick(event: MotionEvent) {
                ImageViewDialog.newInstance(
                    this@MediaDetailsActivity,
                    media.userPreferredName + "[Banner]",
                    media.banner ?: media.cover
                )
                banner.performClick()
            }
        })
        banner.setOnTouchListener { _, motionEvent -> gestureDetector.onTouchEvent(motionEvent);true }
        binding.mediaTitle.text = media.userPreferredName
        binding.mediaTitle.setOnLongClickListener {
            copyToClipboard(media.userPreferredName)
            true
        }
        binding.mediaTitleCollapse.text = media.userPreferredName
        binding.mediaTitleCollapse.setOnLongClickListener {
            copyToClipboard(media.userPreferredName)
            true
        }
        binding.mediaStatus.text = media.status ?: ""

        //Fav Button
        val favButton = if (Anilist.userid != null) {
            if (media.isFav) binding.mediaFav.setImageDrawable(
                AppCompatResources.getDrawable(
                    this,
                    R.drawable.ic_round_favorite_24
                )
            )
            PopImageButton(
                scope,
                binding.mediaFav,
                R.drawable.ic_round_favorite_24,
                R.drawable.ic_round_favorite_border_24,
                R.color.nav_tab,
                R.color.fav,
                media.isFav
            ) {
                media.isFav = it
                Anilist.mutation.toggleFav(media.anime != null, media.id)
                Refresh.all()
            }
        } else {
            binding.mediaFav.visibility = View.GONE
            null
        }

        fun total() {
            val text = SpannableStringBuilder().apply {
                val white = ContextCompat.getColor(this@MediaDetailsActivity, R.color.bg_opp)
                if (media.userStatus != null) {
                    append(if (media.anime != null) getString(R.string.watched_num) else getString(R.string.read_num))
                    val typedValue = TypedValue()
                    theme.resolveAttribute(com.google.android.material.R.attr.colorSecondary, typedValue, true)
                    bold { color(typedValue.data) { append("${media.userProgress}") } }
                    append(if (media.anime != null) getString(R.string.episodes_out_of) else getString(R.string.chapters_out_of))
                } else {
                    append(if (media.anime != null) getString(R.string.episodes_total_of) else getString(R.string.chapters_total_of))
                }
                if (media.anime != null) {
                    if (media.anime!!.nextAiringEpisode != null) {
                        bold { color(white) { append("${media.anime!!.nextAiringEpisode}") } }
                        append(" / ")
                    }
                    bold { color(white) { append("${media.anime!!.totalEpisodes ?: "??"}") } }
                } else
                    bold { color(white) { append("${media.manga!!.totalChapters ?: "??"}") } }
            }
            binding.mediaTotal.text = text
        }

        fun progress() {
            val statuses: Array<String> = resources.getStringArray(R.array.status)
            val statusStrings = if (media.manga==null) resources.getStringArray(R.array.status_anime) else resources.getStringArray(R.array.status_manga)
            val userStatus = if(media.userStatus != null) statusStrings[statuses.indexOf(media.userStatus)] else statusStrings[0]

            if (media.userStatus != null) {
                binding.mediaTotal.visibility = View.VISIBLE
                binding.mediaAddToList.text = userStatus
            } else {
                binding.mediaAddToList.setText(R.string.add)
            }
            total()
            binding.mediaAddToList.setOnClickListener {
                if (Anilist.userid != null) {
                    if (supportFragmentManager.findFragmentByTag("dialog") == null)
                        MediaListDialogFragment().show(supportFragmentManager, "dialog")
                } else snackString(getString(R.string.please_login_anilist))
            }
            binding.mediaAddToList.setOnLongClickListener {
                saveData("${media.id}_progressDialog", true)
                snackString(getString(R.string.auto_update_reset))
                true
            }
        }
        progress()

        model.getMedia().observe(this) {
            if (it != null) {
                media = it
                scope.launch {
                    if(media.isFav!=favButton?.clicked) favButton?.clicked()
                }

                binding.mediaNotify.setOnClickListener {
                    val i = Intent(Intent.ACTION_SEND)
                    i.type = "text/plain"
                    i.putExtra(Intent.EXTRA_TEXT, media.shareLink)
                    startActivity(Intent.createChooser(i, media.userPreferredName))
                }
                binding.mediaNotify.setOnLongClickListener {
                    openLinkInBrowser(media.shareLink)
                    true
                }
                binding.mediaCover.setOnClickListener {
                    openLinkInBrowser(media.shareLink)
                }
                progress()
            }
        }

        adult = media.isAdult

        tabLayout.menu.clear()
        if (media.anime != null) {
            viewPager.adapter = ViewPagerAdapter(supportFragmentManager, lifecycle, SupportedMedia.ANIME)
            tabLayout.inflateMenu(R.menu.anime_menu_detail)
        } else if (media.manga != null) {
            viewPager.adapter = ViewPagerAdapter(supportFragmentManager, lifecycle, if(media.format=="NOVEL") SupportedMedia.NOVEL else SupportedMedia.MANGA)
            tabLayout.inflateMenu(R.menu.manga_menu_detail)
            anime = false
        }


        selected = media.selected!!.window
        binding.mediaTitle.translationX = -screenWidth
        tabLayout.visibility = View.VISIBLE

        tabLayout.setOnItemSelectedListener { item ->
            selectFromID(item.itemId)
            viewPager.setCurrentItem(selected, false)
            val sel = model.loadSelected(media)
            sel.window = selected
            model.saveSelected(media.id, sel, this)
            true
        }


        tabLayout.selectedItemId = idFromSelect()
        viewPager.setCurrentItem(selected, false)

        if (model.continueMedia == null && media.cameFromContinue) {
            model.continueMedia = loadData("continue_media") ?: true
            selected = 1
        }

        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(true) }
        live.observe(this) {
            if (it) {
                scope.launch(Dispatchers.IO) {
                    model.loadMedia(media)
                    live.postValue(false)
                }
            }
        }
    }


    private fun selectFromID(id: Int) {
        when (id) {
            R.id.info             -> {
                selected = 0
            }
            R.id.watch, R.id.read -> {
                selected = 1
            }
        }
    }

    private fun idFromSelect(): Int {
        if (anime) when (selected) {
            0 -> return R.id.info
            1 -> return R.id.watch
        }
        else when (selected) {
            0 -> return R.id.info
            1 -> return R.id.read
        }
        return R.id.info
    }

    override fun onResume() {
        tabLayout.selectedItemId = idFromSelect()
        super.onResume()
    }

    private enum class SupportedMedia{
        ANIME, MANGA, NOVEL
    }
    //ViewPager
    private class ViewPagerAdapter(
        fragmentManager: FragmentManager,
        lifecycle: Lifecycle,
        private val media: SupportedMedia
    ) :
        FragmentStateAdapter(fragmentManager, lifecycle) {

        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment = when (position){
            0 -> MediaInfoFragment()
            1 -> when(media){
                SupportedMedia.ANIME -> AnimeWatchFragment()
                SupportedMedia.MANGA -> MangaReadFragment()
                SupportedMedia.NOVEL -> NovelReadFragment()
            }
            else -> MediaInfoFragment()
        }
    }

    //Collapsing UI Stuff
    private var isCollapsed = false
    private val percent = 45
    private var mMaxScrollSize = 0
    private var screenWidth: Float = 0f

    override fun onOffsetChanged(appBar: AppBarLayout, i: Int) {
        if (mMaxScrollSize == 0) mMaxScrollSize = appBar.totalScrollRange
        val percentage = abs(i) * 100 / mMaxScrollSize

        binding.mediaCover.visibility = if (binding.mediaCover.scaleX == 0f) View.GONE else View.VISIBLE
        val duration = (200 * uiSettings.animationSpeed).toLong()
        if (percentage >= percent && !isCollapsed) {
            isCollapsed = true
            ObjectAnimator.ofFloat(binding.mediaTitle, "translationX", 0f).setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaAccessContainer, "translationX", screenWidth).setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaCover, "translationX", screenWidth).setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaCollapseContainer, "translationX", screenWidth).setDuration(duration).start()
            binding.mediaBanner.pause()
            if (!uiSettings.immersiveMode) this.window.statusBarColor = ContextCompat.getColor(this, R.color.nav_bg)
        }
        if (percentage <= percent && isCollapsed) {
            isCollapsed = false
            ObjectAnimator.ofFloat(binding.mediaTitle, "translationX", -screenWidth).setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaAccessContainer, "translationX", 0f).setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaCover, "translationX", 0f).setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaCollapseContainer, "translationX", 0f).setDuration(duration).start()
            if (uiSettings.bannerAnimations) binding.mediaBanner.resume()
            if (!uiSettings.immersiveMode) this.window.statusBarColor = ContextCompat.getColor(this, R.color.nav_bg_inv)
        }
        if (percentage == 1 && model.scrolledToTop.value != false) model.scrolledToTop.postValue(false)
        if (percentage == 0 && model.scrolledToTop.value != true) model.scrolledToTop.postValue(true)
    }

    class PopImageButton(
        private val scope: CoroutineScope,
        private val image: ImageView,
        private val d1: Int,
        private val d2: Int,
        private val c1: Int,
        private val c2: Int,
        var clicked: Boolean,
        callback: suspend (Boolean) -> (Unit)
    ) {
        private var disabled = false
        private val context = image.context
        private var pressable = true

        init {
            enabled(true)
            scope.launch {
                clicked()
            }
            image.setOnClickListener {
                if (pressable && !disabled) {
                    pressable = false
                    clicked = !clicked
                    scope.launch {
                        launch(Dispatchers.IO) {
                            callback.invoke(clicked)
                        }
                        clicked()
                        pressable = true
                    }
                }
            }
        }

        suspend fun clicked() {
            ObjectAnimator.ofFloat(image, "scaleX", 1f, 0f).setDuration(69).start()
            ObjectAnimator.ofFloat(image, "scaleY", 1f, 0f).setDuration(100).start()
            delay(100)
            if (clicked) {
                ObjectAnimator.ofArgb(image,
                    "ColorFilter",
                    ContextCompat.getColor(context, c1),
                    ContextCompat.getColor(context, c2)
                ).setDuration(120).start()
                image.setImageDrawable(AppCompatResources.getDrawable(context, d1))
            } else image.setImageDrawable(AppCompatResources.getDrawable(context, d2))
            ObjectAnimator.ofFloat(image, "scaleX", 0f, 1.5f).setDuration(120).start()
            ObjectAnimator.ofFloat(image, "scaleY", 0f, 1.5f).setDuration(100).start()
            delay(120)
            ObjectAnimator.ofFloat(image, "scaleX", 1.5f, 1f).setDuration(100).start()
            ObjectAnimator.ofFloat(image, "scaleY", 1.5f, 1f).setDuration(100).start()
            delay(200)
            if (clicked) ObjectAnimator.ofArgb(
                image,
                "ColorFilter",
                ContextCompat.getColor(context, c2),
                ContextCompat.getColor(context, c1)
            ).setDuration(200).start()
        }

        fun enabled(enabled: Boolean) {
            disabled = !enabled
            image.alpha = if(disabled)  0.33f else 1f
        }
    }
}

