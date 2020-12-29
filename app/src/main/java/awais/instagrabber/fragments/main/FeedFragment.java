package awais.instagrabber.fragments.main;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.OnBackPressedDispatcher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.PermissionChecker;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavDirections;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Set;

import awais.instagrabber.R;
import awais.instagrabber.activities.MainActivity;
import awais.instagrabber.adapters.FeedAdapterV2;
import awais.instagrabber.adapters.FeedStoriesAdapter;
import awais.instagrabber.asyncs.FeedPostFetchService;
import awais.instagrabber.customviews.PrimaryActionModeCallback;
import awais.instagrabber.databinding.FragmentFeedBinding;
import awais.instagrabber.dialogs.PostsLayoutPreferencesDialogFragment;
import awais.instagrabber.fragments.PostViewV2Fragment;
import awais.instagrabber.models.FeedModel;
import awais.instagrabber.models.FeedStoryModel;
import awais.instagrabber.models.PostsLayoutPreferences;
import awais.instagrabber.utils.Constants;
import awais.instagrabber.utils.CookieUtils;
import awais.instagrabber.utils.DownloadUtils;
import awais.instagrabber.utils.Utils;
import awais.instagrabber.viewmodels.FeedStoriesViewModel;
import awais.instagrabber.webservices.ServiceCallback;
import awais.instagrabber.webservices.StoriesService;

import static androidx.core.content.PermissionChecker.checkSelfPermission;
import static awais.instagrabber.utils.DownloadUtils.WRITE_PERMISSION;
import static awais.instagrabber.utils.Utils.settingsHelper;

public class FeedFragment extends Fragment implements SwipeRefreshLayout.OnRefreshListener {
    private static final String TAG = "FeedFragment";
    private static final int STORAGE_PERM_REQUEST_CODE = 8020;
    private static final int STORAGE_PERM_REQUEST_CODE_FOR_SELECTION = 8030;

    private MainActivity fragmentActivity;
    private CoordinatorLayout root;
    private FragmentFeedBinding binding;
    private StoriesService storiesService;
    private boolean shouldRefresh = true;
    private FeedStoriesViewModel feedStoriesViewModel;
    private boolean storiesFetching;
    private ActionMode actionMode;
    private Set<FeedModel> selectedFeedModels;
    private FeedModel downloadFeedModel;
    private int downloadChildPosition = -1;
    private PostsLayoutPreferences layoutPreferences = Utils.getPostsLayoutPreferences(Constants.PREF_POSTS_LAYOUT);
    private RecyclerView storiesRecyclerView;

    private final FeedAdapterV2.FeedItemCallback feedItemCallback = new FeedAdapterV2.FeedItemCallback() {
        @Override
        public void onPostClick(final FeedModel feedModel, final View profilePicView, final View mainPostImage) {
            openPostDialog(feedModel, profilePicView, mainPostImage, -1);
        }

        @Override
        public void onSliderClick(final FeedModel feedModel, final int position) {
            openPostDialog(feedModel, null, null, position);
        }

        @Override
        public void onCommentsClick(final FeedModel feedModel) {
            final NavDirections commentsAction = FeedFragmentDirections.actionGlobalCommentsViewerFragment(
                    feedModel.getShortCode(),
                    feedModel.getPostId(),
                    feedModel.getProfileModel().getId()
            );
            NavHostFragment.findNavController(FeedFragment.this).navigate(commentsAction);
        }

        @Override
        public void onDownloadClick(final FeedModel feedModel, final int childPosition) {
            final Context context = getContext();
            if (context == null) return;
            if (checkSelfPermission(context, WRITE_PERMISSION) == PermissionChecker.PERMISSION_GRANTED) {
                DownloadUtils.showDownloadDialog(context, feedModel, childPosition);
                return;
            }
            downloadFeedModel = feedModel;
            downloadChildPosition = childPosition;
            requestPermissions(DownloadUtils.PERMS, STORAGE_PERM_REQUEST_CODE);
        }

        @Override
        public void onHashtagClick(final String hashtag) {
            final NavDirections action = FeedFragmentDirections.actionGlobalHashTagFragment(hashtag);
            NavHostFragment.findNavController(FeedFragment.this).navigate(action);
        }

        @Override
        public void onLocationClick(final FeedModel feedModel) {
            final NavDirections action = FeedFragmentDirections.actionGlobalLocationFragment(feedModel.getLocationId());
            NavHostFragment.findNavController(FeedFragment.this).navigate(action);
        }

        @Override
        public void onMentionClick(final String mention) {
            navigateToProfile(mention.trim());
        }

        @Override
        public void onNameClick(final FeedModel feedModel, final View profilePicView) {
            navigateToProfile("@" + feedModel.getProfileModel().getUsername());
        }

        @Override
        public void onProfilePicClick(final FeedModel feedModel, final View profilePicView) {
            navigateToProfile("@" + feedModel.getProfileModel().getUsername());
        }

        @Override
        public void onURLClick(final String url) {
            Utils.openURL(getContext(), url);
        }

        @Override
        public void onEmailClick(final String emailId) {
            Utils.openEmailAddress(getContext(), emailId);
        }

        private void openPostDialog(final FeedModel feedModel,
                                    final View profilePicView,
                                    final View mainPostImage,
                                    final int position) {
            final PostViewV2Fragment.Builder builder = PostViewV2Fragment
                    .builder(feedModel);
            if (position >= 0) {
                builder.setPosition(position);
            }
            if (!layoutPreferences.isAnimationDisabled()) {
                builder.setSharedProfilePicElement(profilePicView)
                       .setSharedMainPostElement(mainPostImage);
            }
            builder.build().show(getChildFragmentManager(), "post_view");
        }
    };
    private final OnBackPressedCallback onBackPressedCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            binding.feedRecyclerView.endSelection();
        }
    };
    private final PrimaryActionModeCallback multiSelectAction = new PrimaryActionModeCallback(
            R.menu.multi_select_download_menu,
            new PrimaryActionModeCallback.CallbacksHelper() {
                @Override
                public void onDestroy(final ActionMode mode) {
                    binding.feedRecyclerView.endSelection();
                }

                @Override
                public boolean onActionItemClicked(final ActionMode mode, final MenuItem item) {
                    if (item.getItemId() == R.id.action_download) {
                        if (FeedFragment.this.selectedFeedModels == null) return false;
                        final Context context = getContext();
                        if (context == null) return false;
                        if (checkSelfPermission(context, WRITE_PERMISSION) == PermissionChecker.PERMISSION_GRANTED) {
                            DownloadUtils.download(context, ImmutableList.copyOf(FeedFragment.this.selectedFeedModels));
                            binding.feedRecyclerView.endSelection();
                            return true;
                        }
                        requestPermissions(DownloadUtils.PERMS, STORAGE_PERM_REQUEST_CODE_FOR_SELECTION);
                        return true;
                    }
                    return false;
                }
            });
    private final FeedAdapterV2.SelectionModeCallback selectionModeCallback = new FeedAdapterV2.SelectionModeCallback() {

        @Override
        public void onSelectionStart() {
            if (!onBackPressedCallback.isEnabled()) {
                final OnBackPressedDispatcher onBackPressedDispatcher = fragmentActivity.getOnBackPressedDispatcher();
                onBackPressedCallback.setEnabled(true);
                onBackPressedDispatcher.addCallback(getViewLifecycleOwner(), onBackPressedCallback);
            }
            if (actionMode == null) {
                actionMode = fragmentActivity.startActionMode(multiSelectAction);
            }
        }

        @Override
        public void onSelectionChange(final Set<FeedModel> selectedFeedModels) {
            final String title = getString(R.string.number_selected, selectedFeedModels.size());
            if (actionMode != null) {
                actionMode.setTitle(title);
            }
            FeedFragment.this.selectedFeedModels = selectedFeedModels;
        }

        @Override
        public void onSelectionEnd() {
            if (onBackPressedCallback.isEnabled()) {
                onBackPressedCallback.setEnabled(false);
                onBackPressedCallback.remove();
            }
            if (actionMode != null) {
                actionMode.finish();
                actionMode = null;
            }
        }
    };

    private void navigateToProfile(final String username) {
        final NavController navController = NavHostFragment.findNavController(this);
        final Bundle bundle = new Bundle();
        bundle.putString("username", username);
        navController.navigate(R.id.action_global_profileFragment, bundle);
    }

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fragmentActivity = (MainActivity) requireActivity();
        storiesService = StoriesService.getInstance();
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             final ViewGroup container,
                             final Bundle savedInstanceState) {
        if (root != null) {
            shouldRefresh = false;
            if (storiesRecyclerView != null) {
                fragmentActivity.setCollapsingView(storiesRecyclerView);
            }
            return root;
        }
        binding = FragmentFeedBinding.inflate(inflater, container, false);
        root = binding.getRoot();
        return root;
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        if (!shouldRefresh) return;
        binding.feedSwipeRefreshLayout.setOnRefreshListener(this);
        setupFeedStories();
        setupFeed();
        shouldRefresh = false;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu, @NonNull final MenuInflater inflater) {
        inflater.inflate(R.menu.feed_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == R.id.storyList) {
            final NavDirections action = FeedFragmentDirections.actionGlobalStoryListViewerFragment("feed");
            NavHostFragment.findNavController(FeedFragment.this).navigate(action);
        }
        else if (item.getItemId() == R.id.layout) {
            showPostsLayoutPreferences();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateSwipeRefreshState();
        // if (videoAwareRecyclerScroller != null && shouldAutoPlay) {
        //     videoAwareRecyclerScroller.startPlaying();
        // }
    }

    @Override
    public void onRefresh() {
        binding.feedRecyclerView.refresh();
        fetchStories();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (storiesRecyclerView != null) {
            fragmentActivity.removeCollapsingView(storiesRecyclerView);
        }
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, @NonNull final String[] permissions, @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        final boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        final Context context = getContext();
        if (context == null) return;
        if (!granted) {
            Toast.makeText(context, R.string.download_permission, Toast.LENGTH_SHORT).show();
            return;
        }
        if (requestCode == STORAGE_PERM_REQUEST_CODE && granted) {
            if (downloadFeedModel == null) return;
            DownloadUtils.showDownloadDialog(context, downloadFeedModel, downloadChildPosition);
            downloadFeedModel = null;
            downloadChildPosition = -1;
            return;
        }
        if (requestCode == STORAGE_PERM_REQUEST_CODE_FOR_SELECTION && granted) {
            DownloadUtils.download(context, ImmutableList.copyOf(selectedFeedModels));
            binding.feedRecyclerView.endSelection();
        }
    }

    private void setupFeed() {
        binding.feedRecyclerView.setViewModelStoreOwner(this)
                                .setLifeCycleOwner(this)
                                .setPostFetchService(new FeedPostFetchService())
                                .setLayoutPreferences(layoutPreferences)
                                .addFetchStatusChangeListener(fetching -> updateSwipeRefreshState())
                                .setFeedItemCallback(feedItemCallback)
                                .setSelectionModeCallback(selectionModeCallback)
                                .init();
        binding.feedSwipeRefreshLayout.setRefreshing(true);
        // if (shouldAutoPlay) {
        //     videoAwareRecyclerScroller = new VideoAwareRecyclerScroller();
        //     binding.feedRecyclerView.addOnScrollListener(videoAwareRecyclerScroller);
        // }
    }

    private void updateSwipeRefreshState() {
        binding.feedSwipeRefreshLayout.setRefreshing(binding.feedRecyclerView.isFetching() || storiesFetching);
    }

    private void setupFeedStories() {
        feedStoriesViewModel = new ViewModelProvider(fragmentActivity).get(FeedStoriesViewModel.class);
        final FeedStoriesAdapter feedStoriesAdapter = new FeedStoriesAdapter(
            new FeedStoriesAdapter.OnFeedStoryClickListener() {
                @Override
                public void onFeedStoryClick(FeedStoryModel model, int position) {
                    final NavDirections action = FeedFragmentDirections.actionFeedFragmentToStoryViewerFragment(position, null, false, false, null, null);
                    NavHostFragment.findNavController(FeedFragment.this).navigate(action);
                }

                @Override
                public void onFeedStoryLongClick(FeedStoryModel model, int position) {
                    navigateToProfile("@" + model.getProfileModel().getUsername());
                }
            }
        );
        final Context context = getContext();
        if (context == null) return;
        storiesRecyclerView = new RecyclerView(context);
        final CollapsingToolbarLayout.LayoutParams params = new CollapsingToolbarLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                                                     ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, Utils.getActionBarHeight(context), 0, 0);
        storiesRecyclerView.setLayoutParams(params);
        storiesRecyclerView.setClipToPadding(false);
        storiesRecyclerView.setLayoutManager(new LinearLayoutManager(context, RecyclerView.HORIZONTAL, false));
        storiesRecyclerView.setAdapter(feedStoriesAdapter);
        fragmentActivity.setCollapsingView(storiesRecyclerView);
        feedStoriesViewModel.getList().observe(fragmentActivity, list -> {
            Log.d("austin_debug", "observed");
            feedStoriesAdapter.submitList(list);
        });
        fetchStories();
    }

    private void fetchStories() {
        final String cookie = settingsHelper.getString(Constants.COOKIE);
        storiesFetching = true;
        updateSwipeRefreshState();
        storiesService.getFeedStories(CookieUtils.getCsrfTokenFromCookie(cookie), new ServiceCallback<List<FeedStoryModel>>() {
            @Override
            public void onSuccess(final List<FeedStoryModel> result) {
                feedStoriesViewModel.getList().postValue(result);
                storiesFetching = false;
                updateSwipeRefreshState();
            }

            @Override
            public void onFailure(final Throwable t) {
                Log.e(TAG, "failed", t);
                storiesFetching = false;
                updateSwipeRefreshState();
            }
        });
    }

    private void showPostsLayoutPreferences() {
        final PostsLayoutPreferencesDialogFragment fragment = new PostsLayoutPreferencesDialogFragment(
                Constants.PREF_POSTS_LAYOUT,
                preferences -> {
                    layoutPreferences = preferences;
                    new Handler().postDelayed(() -> binding.feedRecyclerView.setLayoutPreferences(preferences), 200);
                });
        fragment.show(getChildFragmentManager(), "posts_layout_preferences");
    }

    public void scrollToTop() {
        binding.feedRecyclerView.smoothScrollToPosition(0);
        // binding.storiesContainer.setExpanded(true);
    }
}
