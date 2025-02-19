package awais.instagrabber.viewmodels;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import awais.instagrabber.models.Resource;
import awais.instagrabber.repositories.responses.User;
import awais.instagrabber.utils.Constants;
import awais.instagrabber.utils.CookieUtils;
import awais.instagrabber.utils.CoroutineUtilsKt;
import awais.instagrabber.utils.TextUtils;
import awais.instagrabber.webservices.UserRepository;
import kotlinx.coroutines.Dispatchers;

import static awais.instagrabber.utils.Utils.settingsHelper;

public class AppStateViewModel extends AndroidViewModel {
    private static final String TAG = AppStateViewModel.class.getSimpleName();

    private final String cookie;
    private final MutableLiveData<Resource<User>> currentUser = new MutableLiveData<>(Resource.loading(null));

    private UserRepository userRepository;

    public AppStateViewModel(@NonNull final Application application) {
        super(application);
        // Log.d(TAG, "AppStateViewModel: constructor");
        cookie = settingsHelper.getString(Constants.COOKIE);
        final boolean isLoggedIn = !TextUtils.isEmpty(cookie) && CookieUtils.getUserIdFromCookie(cookie) != 0;
        if (!isLoggedIn) {
            currentUser.postValue(Resource.success(null));
            return;
        }
        userRepository = UserRepository.Companion.getInstance();
        // final AccountRepository accountRepository = AccountRepository.getInstance(AccountDataSource.getInstance(application));
        fetchProfileDetails();
    }

    @Nullable
    public Resource<User> getCurrentUser() {
        return currentUser.getValue();
    }

    public LiveData<Resource<User>> getCurrentUserLiveData() {
        return currentUser;
    }

    public void fetchProfileDetails() {
        currentUser.postValue(Resource.loading(null));
        final long uid = CookieUtils.getUserIdFromCookie(cookie);
        if (userRepository == null) {
            currentUser.postValue(Resource.success(null));
            return;
        }
        userRepository.getUserInfo(uid, CoroutineUtilsKt.getContinuation((user, throwable) -> {
            if (throwable != null) {
                Log.e(TAG, "onFailure: ", throwable);
                final User backup = currentUser.getValue().data != null ?
                                        currentUser.getValue().data :
                                        new User(uid);
                currentUser.postValue(Resource.error(throwable.getMessage(), backup));
                return;
            }
            currentUser.postValue(Resource.success(user));
        }, Dispatchers.getIO()));
    }
}
