/*
 * Copyright the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.send;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnFocusChangeListener;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.MenuProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import com.google.common.base.Joiner;
import com.google.common.util.concurrent.ListenableFuture;
import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.R;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.addressbook.AddressBookDao;
import de.schildbach.wallet.addressbook.AddressBookDatabase;
import de.schildbach.wallet.addressbook.AddressBookEntry;
import de.schildbach.wallet.data.PaymentIntent;
import de.schildbach.wallet.data.PaymentIntent.Standard;
import de.schildbach.wallet.offline.DirectPaymentTask;
import de.schildbach.wallet.service.BlockchainState;
import de.schildbach.wallet.ui.AbstractWalletActivity;
import de.schildbach.wallet.ui.AbstractWalletActivityViewModel;
import de.schildbach.wallet.ui.AddressAndLabel;
import de.schildbach.wallet.ui.CurrencyAmountView;
import de.schildbach.wallet.ui.CurrencyCalculatorLink;
import de.schildbach.wallet.ui.DialogBuilder;
import de.schildbach.wallet.ui.InputParser.BinaryInputParser;
import de.schildbach.wallet.ui.InputParser.StreamInputParser;
import de.schildbach.wallet.ui.InputParser.StringInputParser;
import de.schildbach.wallet.ui.ProgressDialogFragment;
import de.schildbach.wallet.ui.RequestEnableBluetooth;
import de.schildbach.wallet.ui.TransactionsAdapter;
import de.schildbach.wallet.ui.scan.ScanActivity;
import de.schildbach.wallet.util.Bluetooth;
import de.schildbach.wallet.util.Nfc;
import de.schildbach.wallet.util.WalletUtils;
import org.bitcoin.protocols.payments.Protos.Payment;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.PrefixedChecksummedBytes;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionConfidence.ConfidenceType;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.protocols.payments.PaymentProtocol;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.KeyChain.KeyPurpose;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.BalanceType;
import org.bitcoinj.wallet.Wallet.CouldNotAdjustDownwards;
import org.bitcoinj.wallet.Wallet.DustySendRequested;
import org.bouncycastle.crypto.params.KeyParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author Andreas Schildbach
 */
public final class SendCoinsFragment extends Fragment {
    private AbstractWalletActivity activity;
    private WalletApplication application;
    private Configuration config;
    private AddressBookDao addressBookDao;
    private ContentResolver contentResolver;
    private FragmentManager fragmentManager;
    @Nullable
    private BluetoothAdapter bluetoothAdapter;

    private final Handler handler = new Handler();
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private View payeeGroup;
    private TextView payeeNameView;
    private TextView payeeVerifiedByView;
    private AutoCompleteTextView receivingAddressView;
    private ReceivingAddressViewAdapter receivingAddressViewAdapter;
    private View receivingStaticView;
    private TextView receivingStaticAddressView;
    private TextView receivingStaticLabelView;
    private View amountGroup;
    private CurrencyCalculatorLink amountCalculatorLink;
    private CheckBox directPaymentEnableView;

    private TextView hintView;
    private TextView directPaymentMessageView;
    private ViewGroup sentTransactionView;
    private TransactionsAdapter.TransactionViewHolder sentTransactionViewHolder;
    private View privateKeyPasswordViewGroup;
    private EditText privateKeyPasswordView;
    private View privateKeyBadPasswordView;
    private Button viewGo;
    private Button viewCancel;

    private AbstractWalletActivityViewModel walletActivityViewModel;
    private SendCoinsViewModel viewModel;

    private static final Logger log = LoggerFactory.getLogger(SendCoinsFragment.class);

    private final ActivityResultLauncher<Void> scanLauncher =
            registerForActivityResult(new ScanActivity.Scan(), input -> {
                if (input == null) return;
                new StringInputParser(input) {
                    @Override
                    protected void handlePaymentIntent(final PaymentIntent paymentIntent) {
                        setState(null);

                        updateStateFrom(paymentIntent);
                    }

                    @Override
                    protected void handleDirectTransaction(final Transaction transaction) throws VerificationException {
                        cannotClassify(input);
                    }

                    @Override
                    protected void error(final int messageResId, final Object... messageArgs) {
                        final DialogBuilder dialog = DialogBuilder.dialog(activity, R.string.button_scan, messageResId, messageArgs);
                        dialog.singleDismissButton(null);
                        dialog.show();
                    }
                }.parse();
            });
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted)
                    maybeEnableBluetooth();
                else
                    directPaymentEnableView.setChecked(false);
            });
    private final ActivityResultLauncher<Void> requestEnableBluetoothForPaymentRequestLauncher =
            registerForActivityResult(new RequestEnableBluetooth(), enabled -> {
                if (viewModel.paymentIntent.isBluetoothPaymentRequestUrl())
                    requestPaymentRequest();
            });
    private final ActivityResultLauncher<Void> requestEnableBluetoothForDirectPaymentLauncher =
            registerForActivityResult(new RequestEnableBluetooth(), enabled -> {
                if (viewModel.paymentIntent.isBluetoothPaymentUrl())
                    directPaymentEnableView.setChecked(enabled);
            });

    private final class ReceivingAddressListener
            implements OnFocusChangeListener, TextWatcher, AdapterView.OnItemClickListener {
        @Override
        public void onFocusChange(final View v, final boolean hasFocus) {
            if (!hasFocus) {
                validateReceivingAddress();
                updateView();
            }
        }

        @Override
        public void afterTextChanged(final Editable s) {
            final String constraint = s.toString().trim();
            if (!constraint.isEmpty())
                validateReceivingAddress();
            else
                updateView();
        }

        @Override
        public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {
        }

        @Override
        public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
        }

        @Override
        public void onItemClick(final AdapterView<?> parent, final View view, final int position, final long id) {
            final AddressBookEntry entry = receivingAddressViewAdapter.getItem(position);
            try {
                viewModel.validatedAddress = new AddressAndLabel(Constants.NETWORK_PARAMETERS, entry.getAddress(),
                        entry.getLabel());
                receivingAddressView.setText(null);
                log.info("Picked valid address from suggestions: {}", viewModel.validatedAddress);
            } catch (final AddressFormatException x) {
                // swallow
            }
        }
    }

    private final ReceivingAddressListener receivingAddressListener = new ReceivingAddressListener();

    private final CurrencyAmountView.Listener amountsListener = new CurrencyAmountView.Listener() {
        @Override
        public void changed() {
            viewModel.amount.setValue(amountCalculatorLink.getAmount());
        }

        @Override
        public void focusChanged(final boolean hasFocus) {
        }
    };

    private final TextWatcher privateKeyPasswordListener = new TextWatcher() {
        @Override
        public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
            privateKeyBadPasswordView.setVisibility(View.INVISIBLE);
            updateView();
        }

        @Override
        public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {
        }

        @Override
        public void afterTextChanged(final Editable s) {
        }
    };

    private final class ReceivingAddressViewAdapter extends ArrayAdapter<AddressBookEntry> {
        private final LayoutInflater inflater;

        public ReceivingAddressViewAdapter(final Context context) {
            super(context, 0);
            this.inflater = LayoutInflater.from(context);
        }

        @Override
        public View getView(final int position, View view, final ViewGroup parent) {
            if (view == null)
                view = inflater.inflate(R.layout.send_coins_address_row, parent, false);
            final AddressBookEntry entry = getItem(position);
            ((TextView) view.findViewById(R.id.send_coins_address_row_label)).setText(entry.getLabel());
            ((TextView) view.findViewById(R.id.send_coins_address_row_address)).setText(WalletUtils.formatHash(
                    entry.getAddress(), Constants.ADDRESS_FORMAT_GROUP_SIZE, Constants.ADDRESS_FORMAT_LINE_SIZE));
            return view;
        }

        @Override
        public Filter getFilter() {
            return new Filter() {
                @Override
                protected FilterResults performFiltering(final CharSequence constraint) {
                    final String trimmedConstraint = constraint.toString().trim();
                    final FilterResults results = new FilterResults();
                    if (viewModel.validatedAddress == null && !trimmedConstraint.isEmpty()) {
                        final List<AddressBookEntry> entries = addressBookDao.get(trimmedConstraint);
                        results.values = entries;
                        results.count = entries.size();
                    } else {
                        results.values = Collections.emptyList();
                        results.count = 0;
                    }
                    return results;
                }

                @Override
                protected void publishResults(final CharSequence constraint, final FilterResults results) {
                    setNotifyOnChange(false);
                    clear();
                    if (results.count > 0)
                        addAll((List<AddressBookEntry>) results.values);
                    notifyDataSetChanged();
                }
            };
        }
    }

    private final DialogInterface.OnClickListener activityDismissListener = new DialogInterface.OnClickListener() {
        @Override
        public void onClick(final DialogInterface dialog, final int which) {
            activity.finish();
        }
    };

    @Override
    public void onAttach(final Context context) {
        super.onAttach(context);
        this.activity = (AbstractWalletActivity) context;
        this.application = activity.getWalletApplication();
        this.config = application.getConfiguration();
        this.addressBookDao = AddressBookDatabase.getDatabase(context).addressBookDao();
        this.contentResolver = application.getContentResolver();
        this.bluetoothAdapter = application.getSystemService(BluetoothManager.class).getAdapter();
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.fragmentManager = getChildFragmentManager();

        walletActivityViewModel = new ViewModelProvider(activity).get(AbstractWalletActivityViewModel.class);
        walletActivityViewModel.wallet.observe(this, wallet -> updateView());
        viewModel = new ViewModelProvider(this).get(SendCoinsViewModel.class);
        viewModel.addressBook.observe(this, addressBook -> updateView());
        if (config.isEnableExchangeRates()) {
            viewModel.exchangeRate.observe(this, exchangeRate -> {
                final SendCoinsViewModel.State state = viewModel.state;
                if (state == null || state.compareTo(SendCoinsViewModel.State.INPUT) <= 0)
                    amountCalculatorLink.setExchangeRate(exchangeRate != null ? exchangeRate.exchangeRate() : null);
            });
        }
        viewModel.dynamicFees.observe(this, dynamicFees -> updateView());
        viewModel.feeCategory.observe(this, feeCategory -> updateView());
        application.blockchainState.observe(this, blockchainState -> updateView());
        viewModel.balance.observe(this, coin -> activity.invalidateOptionsMenu());
        viewModel.progress.observe(this, new ProgressDialogFragment.Observer(fragmentManager));
        viewModel.sentTransaction.observe(this, transaction -> {
            if (viewModel.state == SendCoinsViewModel.State.SENDING) {
                final TransactionConfidence confidence = transaction.getConfidence();
                final ConfidenceType confidenceType = confidence.getConfidenceType();
                final int numBroadcastPeers = confidence.numBroadcastPeers();
                if (confidenceType == ConfidenceType.DEAD)
                    setState(SendCoinsViewModel.State.FAILED);
                else if (numBroadcastPeers > 1 || confidenceType == ConfidenceType.BUILDING)
                    setState(SendCoinsViewModel.State.SENT);
            }
            updateView();
        });
        viewModel.amount.observe(this, amount -> {
            updateView();
            viewModel.maybeDryrun();
        });
        viewModel.visibleAmount.observe(this, amount -> amountCalculatorLink.setBtcAmount(amount));
        viewModel.dryrunTransaction.observe(this, transaction -> updateView());
        viewModel.dryrunException.observe(this, e -> updateView());

        backgroundThread = new HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND);
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        activity.addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(final Menu menu, final MenuInflater inflater) {
                inflater.inflate(R.menu.send_coins_fragment_options, menu);
            }

            @Override
            public void onPrepareMenu(final Menu menu) {
                final MenuItem scanAction = menu.findItem(R.id.send_coins_options_scan);
                final PackageManager pm = activity.getPackageManager();
                scanAction.setVisible(pm.hasSystemFeature(PackageManager.FEATURE_CAMERA)
                        || pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT));
                scanAction.setEnabled(viewModel.state == SendCoinsViewModel.State.INPUT);

                final MenuItem emptyAction = menu.findItem(R.id.send_coins_options_empty);
                emptyAction.setEnabled(viewModel.state == SendCoinsViewModel.State.INPUT
                        && viewModel.paymentIntent.mayEditAmount() && viewModel.balance.getValue() != null);

                final MenuItem feeCategoryAction = menu.findItem(R.id.send_coins_options_fee_category);
                final FeeCategory feeCategory = viewModel.feeCategory.getValue();
                feeCategoryAction.setEnabled(viewModel.state == SendCoinsViewModel.State.INPUT);
                if (feeCategory == FeeCategory.ECONOMIC)
                    menu.findItem(R.id.send_coins_options_fee_category_economic).setChecked(true);
                else if (feeCategory == FeeCategory.NORMAL)
                    menu.findItem(R.id.send_coins_options_fee_category_normal).setChecked(true);
                else if (feeCategory == FeeCategory.PRIORITY)
                    menu.findItem(R.id.send_coins_options_fee_category_priority).setChecked(true);
            }

            @Override
            public boolean onMenuItemSelected(final MenuItem item) {
                int itemId = item.getItemId();
                if (itemId == R.id.send_coins_options_scan) {
                    scanLauncher.launch(null);
                    return true;
                } else if (itemId == R.id.send_coins_options_fee_category_economic) {
                    handleFeeCategory(FeeCategory.ECONOMIC);
                    return true;
                } else if (itemId == R.id.send_coins_options_fee_category_normal) {
                    handleFeeCategory(FeeCategory.NORMAL);
                    return true;
                } else if (itemId == R.id.send_coins_options_fee_category_priority) {
                    handleFeeCategory(FeeCategory.PRIORITY);
                    return true;
                } else if (itemId == R.id.send_coins_options_empty) {
                    handleEmpty();
                    return true;
                }
                return false;
            }
        });

        if (savedInstanceState == null) {
            final Intent intent = activity.getIntent();
            final String action = intent.getAction();
            final Uri intentUri = intent.getData();
            final String scheme = intentUri != null ? intentUri.getScheme() : null;
            final String mimeType = intent.getType();

            if ((Intent.ACTION_VIEW.equals(action) || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action))
                    && intentUri != null && "bitcoin".equals(scheme)) {
                initStateFromBitcoinUri(intentUri);
            } else if ((NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action))
                    && PaymentProtocol.MIMETYPE_PAYMENTREQUEST.equals(mimeType)) {
                final NdefMessage ndefMessage = (NdefMessage) intent
                        .getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)[0];
                final byte[] ndefMessagePayload = Nfc.extractMimePayload(PaymentProtocol.MIMETYPE_PAYMENTREQUEST,
                        ndefMessage);
                initStateFromPaymentRequest(mimeType, ndefMessagePayload);
            } else if ((Intent.ACTION_VIEW.equals(action))
                    && PaymentProtocol.MIMETYPE_PAYMENTREQUEST.equals(mimeType)) {
                final byte[] paymentRequest = paymentRequestFromIntent(intent);

                if (intentUri != null)
                    initStateFromIntentUri(mimeType, intentUri);
                else if (paymentRequest != null)
                    initStateFromPaymentRequest(mimeType, paymentRequest);
                else
                    throw new IllegalArgumentException();
            } else if (intent.hasExtra(SendCoinsActivity.INTENT_EXTRA_PAYMENT_INTENT)) {
                initStateFromIntentExtras(intent.getExtras());
            } else {
                updateStateFrom(PaymentIntent.blank());
            }
        }
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
            final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.send_coins_fragment, container, false);
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            final Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            final boolean imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime());
            if (imeVisible) {
                final Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
                v.setPadding(insets.left, v.getPaddingTop(), insets.right, ime.bottom);
            } else {
                v.setPadding(insets.left, v.getPaddingTop(), insets.right, insets.bottom);
            }
            final LinearLayout layout = (LinearLayout) v;
            if (insets.bottom > 0 && !imeVisible)
                layout.setShowDividers(layout.getShowDividers() | LinearLayout.SHOW_DIVIDER_END);
            else
                layout.setShowDividers(layout.getShowDividers() & ~LinearLayout.SHOW_DIVIDER_END);
            return windowInsets;
        });

        payeeGroup = view.findViewById(R.id.send_coins_payee_group);

        payeeNameView = view.findViewById(R.id.send_coins_payee_name);
        payeeVerifiedByView = view.findViewById(R.id.send_coins_payee_verified_by);

        receivingAddressView = view.findViewById(R.id.send_coins_receiving_address);
        receivingAddressViewAdapter = new ReceivingAddressViewAdapter(activity);
        receivingAddressView.setAdapter(receivingAddressViewAdapter);
        receivingAddressView.setOnFocusChangeListener(receivingAddressListener);
        receivingAddressView.addTextChangedListener(receivingAddressListener);
        receivingAddressView.setOnItemClickListener(receivingAddressListener);

        receivingStaticView = view.findViewById(R.id.send_coins_receiving_static);
        receivingStaticAddressView = view.findViewById(R.id.send_coins_receiving_static_address);
        receivingStaticLabelView = view.findViewById(R.id.send_coins_receiving_static_label);

        amountGroup = view.findViewById(R.id.send_coins_amount_group);

        final CurrencyAmountView btcAmountView = view.findViewById(R.id.send_coins_amount_btc);
        btcAmountView.setCurrencySymbol(config.getFormat().code());
        btcAmountView.setInputFormat(config.getMaxPrecisionFormat());
        btcAmountView.setHintFormat(config.getFormat());

        final CurrencyAmountView localAmountView = view.findViewById(R.id.send_coins_amount_local);
        localAmountView.setInputFormat(Constants.LOCAL_FORMAT);
        localAmountView.setHintFormat(Constants.LOCAL_FORMAT);
        localAmountView.setVisibility(config.isEnableExchangeRates() ? View.VISIBLE : View.GONE);
        amountCalculatorLink = new CurrencyCalculatorLink(btcAmountView, localAmountView);
        amountCalculatorLink.setExchangeDirection(config.getLastExchangeDirection());

        directPaymentEnableView = view.findViewById(R.id.send_coins_direct_payment_enable);
        directPaymentEnableView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (viewModel.paymentIntent.isBluetoothPaymentUrl() && isChecked)
                maybeEnableBluetooth();
        });

        hintView = view.findViewById(R.id.send_coins_hint);

        directPaymentMessageView = view.findViewById(R.id.send_coins_direct_payment_message);

        sentTransactionView = view.findViewById(R.id.transaction_row);
        sentTransactionView.setVisibility(View.GONE);
        sentTransactionView.setLayoutAnimation(AnimationUtils.loadLayoutAnimation(activity,
                R.anim.transaction_layout_anim));
        sentTransactionViewHolder = new TransactionsAdapter.TransactionViewHolder(view);

        privateKeyPasswordViewGroup = view.findViewById(R.id.send_coins_private_key_password_group);
        privateKeyPasswordView = view.findViewById(R.id.send_coins_private_key_password);
        privateKeyBadPasswordView = view.findViewById(R.id.send_coins_private_key_bad_password);

        viewGo = view.findViewById(R.id.send_coins_go);
        viewGo.setOnClickListener(v -> {
            validateReceivingAddress();

            if (everythingPlausible())
                handleGo();
            else
                requestFocusFirst();

            updateView();
        });

        viewCancel = view.findViewById(R.id.send_coins_cancel);
        viewCancel.setOnClickListener(v -> handleCancel());

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        config.setLastExchangeDirection(amountCalculatorLink.getExchangeDirection());
    }

    @Override
    public void onResume() {
        super.onResume();

        amountCalculatorLink.setListener(amountsListener);
        privateKeyPasswordView.addTextChangedListener(privateKeyPasswordListener);

        updateView();
    }

    @Override
    public void onPause() {
        privateKeyPasswordView.removeTextChangedListener(privateKeyPasswordListener);
        amountCalculatorLink.setListener(null);

        super.onPause();
    }

    @Override
    public void onDetach() {
        handler.removeCallbacksAndMessages(null);

        super.onDetach();
    }

    @Override
    public void onDestroy() {
        backgroundThread.getLooper().quit();
        super.onDestroy();
    }

    private void validateReceivingAddress() {
        try {
            final String addressStr = receivingAddressView.getText().toString().trim();
            if (!addressStr.isEmpty()) {
                final Address address = Address.fromString(Constants.NETWORK_PARAMETERS, addressStr);
                final String label = addressBookDao.resolveLabel(address.toString());
                viewModel.validatedAddress = new AddressAndLabel(Constants.NETWORK_PARAMETERS, address.toString(),
                        label);
                receivingAddressView.setText(null);
                log.info("Locked to valid address: {}", viewModel.validatedAddress);
            }
        } catch (final AddressFormatException x) {
            // swallow
        }
    }

    private void handleCancel() {
        if (viewModel.state == null || viewModel.state.compareTo(SendCoinsViewModel.State.INPUT) <= 0)
            activity.setResult(Activity.RESULT_CANCELED);

        activity.finish();
    }

    private boolean isPayeePlausible() {
        if (viewModel.paymentIntent.hasOutputs())
            return true;

        if (viewModel.validatedAddress != null)
            return true;

        return false;
    }

    private boolean isAmountPlausible() {
        if (viewModel.dryrunTransaction.getValue() != null)
            return viewModel.dryrunException.getValue() == null;
        else if (viewModel.paymentIntent.mayEditAmount())
            return viewModel.amount.getValue() != null;
        else
            return viewModel.paymentIntent.hasAmount();
    }

    private boolean isPasswordPlausible() {
        final Wallet wallet = walletActivityViewModel.wallet.getValue();
        if (wallet == null)
            return false;
        if (!wallet.isEncrypted())
            return true;
        return !privateKeyPasswordView.getText().toString().trim().isEmpty();
    }

    private boolean everythingPlausible() {
        return viewModel.state == SendCoinsViewModel.State.INPUT && isPayeePlausible() && isAmountPlausible()
                && isPasswordPlausible();
    }

    private void requestFocusFirst() {
        if (!isPayeePlausible())
            receivingAddressView.requestFocus();
        else if (!isAmountPlausible())
            amountCalculatorLink.requestFocus();
        else if (!isPasswordPlausible())
            privateKeyPasswordView.requestFocus();
        else if (everythingPlausible())
            viewGo.requestFocus();
        else
            log.warn("unclear focus");
    }

    private void handleGo() {
        privateKeyBadPasswordView.setVisibility(View.INVISIBLE);

        final Wallet wallet = walletActivityViewModel.wallet.getValue();
        if (wallet.isEncrypted()) {
            new DeriveKeyTask(backgroundHandler, application.scryptIterationsTarget()) {
                @Override
                protected void onSuccess(final KeyParameter encryptionKey, final boolean wasChanged) {
                    if (wasChanged)
                        WalletUtils.autoBackupWallet(activity, wallet);
                    signAndSendPayment(encryptionKey);
                }
            }.deriveKey(wallet, privateKeyPasswordView.getText().toString().trim());

            setState(SendCoinsViewModel.State.DECRYPTING);
        } else {
            signAndSendPayment(null);
        }
    }

    private void signAndSendPayment(final KeyParameter encryptionKey) {
        setState(SendCoinsViewModel.State.SIGNING);

        // final payment intent
        final Coin amount = viewModel.amount.getValue();
        final PaymentIntent finalPaymentIntent = viewModel.paymentIntent.mergeWithEditedValues(
                amount, viewModel.validatedAddress != null ? viewModel.validatedAddress.address : null);
        final Coin finalAmount = finalPaymentIntent.getAmount();

        // prepare send request
        final Map<FeeCategory, Coin> fees = viewModel.dynamicFees.getValue();
        final Wallet wallet = walletActivityViewModel.wallet.getValue();
        final SendRequest sendRequest = finalPaymentIntent.toSendRequest();
        sendRequest.emptyWallet =
                viewModel.paymentIntent.mayEditAmount() && amount.equals(Constants.NETWORK_PARAMETERS.getMaxMoney());
        sendRequest.feePerKb = fees.get(viewModel.feeCategory.getValue());
        sendRequest.memo = viewModel.paymentIntent.memo;
        sendRequest.exchangeRate = amountCalculatorLink.getExchangeRate();
        sendRequest.aesKey = encryptionKey;

        final Coin fee = viewModel.dryrunTransaction.getValue().getFee();
        if (fee.isGreaterThan(finalAmount)) {
            setState(SendCoinsViewModel.State.INPUT);

            final MonetaryFormat btcFormat = config.getFormat();
            final DialogBuilder dialog = DialogBuilder.warn(activity,
                    R.string.send_coins_fragment_significant_fee_title,
                    R.string.send_coins_fragment_significant_fee_message, btcFormat.format(fee),
                    btcFormat.format(finalAmount));
            dialog.setPositiveButton(R.string.send_coins_fragment_button_send, (d, which) -> sendPayment(sendRequest, finalAmount));
            dialog.setNegativeButton(R.string.button_cancel, null);
            dialog.show();
        } else {
            sendPayment(sendRequest, finalAmount);
        }
    }

    private void sendPayment(final SendRequest sendRequest, final Coin finalAmount) {
        final Wallet wallet = walletActivityViewModel.wallet.getValue();
        new SendCoinsOfflineTask(wallet, backgroundHandler) {
            @Override
            protected void onSuccess(final Transaction transaction) {
                viewModel.sentTransaction.setValue(transaction);
                setState(SendCoinsViewModel.State.SENDING);

                final Address refundAddress = viewModel.paymentIntent.standard == Standard.BIP70
                        ? wallet.freshAddress(KeyPurpose.REFUND) : null;
                final Payment payment = PaymentProtocol.createPaymentMessage(Collections.singletonList(transaction),
                        finalAmount, refundAddress, null, viewModel.paymentIntent.payeeData);

                if (directPaymentEnableView.isChecked())
                    directPay(payment);

                final ListenableFuture<Transaction> future = walletActivityViewModel.broadcastTransaction(transaction);
                future.addListener(() -> {
                    // Auto-close the dialog after a short delay
                    if (config.getSendCoinsAutoclose())
                        handler.postDelayed(() -> activity.finish(), Constants.AUTOCLOSE_DELAY_MS);
                }, Threading.THREAD_POOL);

                final ComponentName callingActivity = activity.getCallingActivity();
                if (callingActivity != null) {
                    log.info("returning result to calling activity: {}", callingActivity.flattenToString());

                    final Intent result = new Intent();
                    transactionHashToResult(result, transaction.getTxId().toString());
                    if (viewModel.paymentIntent.standard == Standard.BIP70)
                        paymentToResult(result, payment.toByteArray());
                    activity.setResult(Activity.RESULT_OK, result);
                }
            }

            private void directPay(final Payment payment) {
                final DirectPaymentTask.ResultCallback callback = new DirectPaymentTask.ResultCallback() {
                    @Override
                    public void onResult(final boolean ack) {
                        viewModel.directPaymentAck = ack;

                        if (viewModel.state == SendCoinsViewModel.State.SENDING)
                            setState(SendCoinsViewModel.State.SENT);

                        updateView();
                    }

                    @Override
                    public void onFail(final int messageResId, final Object... messageArgs) {
                        final DialogBuilder dialog = DialogBuilder.warn(activity,
                                R.string.send_coins_fragment_direct_payment_failed_title,
                                viewModel.paymentIntent.paymentUrl + "\n" + getString(messageResId, messageArgs)
                                        + "\n\n" + getString(R.string.send_coins_fragment_direct_payment_failed_msg));
                        dialog.setPositiveButton(R.string.button_retry, (d, which) -> directPay(payment));
                        dialog.setNegativeButton(R.string.button_dismiss, null);
                        dialog.show();
                    }
                };

                if (viewModel.paymentIntent.isHttpPaymentUrl()) {
                    new DirectPaymentTask.HttpPaymentTask(backgroundHandler, callback,
                            viewModel.paymentIntent.paymentUrl, application.httpUserAgent()).send(payment);
                } else if (viewModel.paymentIntent.isBluetoothPaymentUrl() && bluetoothAdapter != null
                        && bluetoothAdapter.isEnabled()) {
                    new DirectPaymentTask.BluetoothPaymentTask(backgroundHandler, callback, bluetoothAdapter,
                            Bluetooth.getBluetoothMac(viewModel.paymentIntent.paymentUrl)).send(payment);
                }
            }

            @Override
            protected void onInsufficientMoney(final Coin missing) {
                setState(SendCoinsViewModel.State.INPUT);

                final Coin estimated = wallet.getBalance(BalanceType.ESTIMATED);
                final Coin available = wallet.getBalance(BalanceType.AVAILABLE);
                final Coin pending = estimated.subtract(available);

                final MonetaryFormat btcFormat = config.getFormat();

                final StringBuilder msg = new StringBuilder();
                msg.append(getString(R.string.send_coins_fragment_insufficient_money_msg1, btcFormat.format(missing)));
                if (pending.signum() > 0)
                    msg.append("\n\n")
                            .append(getString(R.string.send_coins_fragment_pending, btcFormat.format(pending)));
                if (viewModel.paymentIntent.mayEditAmount())
                    msg.append("\n\n").append(getString(R.string.send_coins_fragment_insufficient_money_msg2));

                final DialogBuilder dialog = DialogBuilder.warn(activity, R.string.send_coins_fragment_insufficient_money_title, msg);
                if (viewModel.paymentIntent.mayEditAmount()) {
                    dialog.setPositiveButton(R.string.send_coins_options_empty, (d, which) -> handleEmpty());
                    dialog.setNegativeButton(R.string.button_cancel, null);
                } else {
                    dialog.setNeutralButton(R.string.button_dismiss, null);
                }
                dialog.show();
            }

            @Override
            protected void onInvalidEncryptionKey() {
                setState(SendCoinsViewModel.State.INPUT);

                privateKeyBadPasswordView.setVisibility(View.VISIBLE);
                privateKeyPasswordView.requestFocus();
            }

            @Override
            protected void onEmptyWalletFailed(Exception exception) {
                setState(SendCoinsViewModel.State.INPUT);

                final DialogBuilder dialog = DialogBuilder.warn(activity,
                        R.string.send_coins_fragment_empty_wallet_failed_title,
                        R.string.send_coins_fragment_hint_empty_wallet_failed);
                dialog.setNeutralButton(R.string.button_dismiss, null);
                dialog.show();
            }

            @Override
            protected void onFailure(Exception exception) {
                setState(SendCoinsViewModel.State.FAILED);

                final DialogBuilder dialog = DialogBuilder.warn(activity, R.string.send_coins_error_msg,
                        exception.toString());
                dialog.setNeutralButton(R.string.button_dismiss, null);
                dialog.show();
            }
        }.sendCoinsOffline(sendRequest); // send asynchronously
    }

    private void handleFeeCategory(final FeeCategory feeCategory) {
        log.info("switching to {} fee category", feeCategory);
        viewModel.feeCategory.setValue(feeCategory);
    }

    private void handleEmpty() {
        viewModel.amount.setValue(Constants.NETWORK_PARAMETERS.getMaxMoney());
    }

    private void setState(final SendCoinsViewModel.State state) {
        viewModel.state = state;

        activity.invalidateOptionsMenu();
        updateView();
    }

    private void updateView() {
        final Wallet wallet = walletActivityViewModel.wallet.getValue();
        final Map<FeeCategory, Coin> fees = viewModel.dynamicFees.getValue();
        final BlockchainState blockchainState = application.blockchainState.getValue();
        final Map<String, AddressBookEntry> addressBook = AddressBookEntry.asMap(viewModel.addressBook.getValue());
        final Transaction dryrunTransaction = viewModel.dryrunTransaction.getValue();
        final Exception dryrunException = viewModel.dryrunException.getValue();

        if (viewModel.paymentIntent != null) {
            final MonetaryFormat btcFormat = config.getFormat();

            getView().setVisibility(View.VISIBLE);

            if (viewModel.paymentIntent.hasPayee()) {
                payeeNameView.setVisibility(View.VISIBLE);
                payeeNameView.setText(viewModel.paymentIntent.payeeName);

                payeeVerifiedByView.setVisibility(View.VISIBLE);
                final String verifiedBy = viewModel.paymentIntent.payeeVerifiedBy != null
                        ? viewModel.paymentIntent.payeeVerifiedBy
                        : getString(R.string.send_coins_fragment_payee_verified_by_unknown);
                payeeVerifiedByView.setText(Constants.CHAR_CHECKMARK
                        + String.format(getString(R.string.send_coins_fragment_payee_verified_by), verifiedBy));
            } else {
                payeeNameView.setVisibility(View.GONE);
                payeeVerifiedByView.setVisibility(View.GONE);
            }

            if (viewModel.paymentIntent.hasOutputs()) {
                payeeGroup.setVisibility(View.VISIBLE);
                receivingAddressView.setVisibility(View.GONE);
                receivingStaticView.setVisibility(
                        !viewModel.paymentIntent.hasPayee() || viewModel.paymentIntent.payeeVerifiedBy == null
                                ? View.VISIBLE : View.GONE);

                receivingStaticLabelView.setText(viewModel.paymentIntent.memo);

                if (viewModel.paymentIntent.hasAddress())
                    receivingStaticAddressView.setText(WalletUtils.formatAddress(viewModel.paymentIntent.getAddress(),
                            Constants.ADDRESS_FORMAT_GROUP_SIZE, Constants.ADDRESS_FORMAT_LINE_SIZE));
                else
                    receivingStaticAddressView.setText(R.string.send_coins_fragment_receiving_address_complex);
            } else if (viewModel.validatedAddress != null) {
                payeeGroup.setVisibility(View.VISIBLE);
                receivingAddressView.setVisibility(View.GONE);
                receivingStaticView.setVisibility(View.VISIBLE);

                receivingStaticAddressView.setText(WalletUtils.formatAddress(viewModel.validatedAddress.address,
                        Constants.ADDRESS_FORMAT_GROUP_SIZE, Constants.ADDRESS_FORMAT_LINE_SIZE));
                final String addressBookLabel = addressBookDao
                        .resolveLabel(viewModel.validatedAddress.address.toString());
                final String staticLabel;
                if (addressBookLabel != null)
                    staticLabel = addressBookLabel;
                else if (viewModel.validatedAddress.label != null)
                    staticLabel = viewModel.validatedAddress.label;
                else
                    staticLabel = getString(R.string.address_unlabeled);
                receivingStaticLabelView.setText(staticLabel);
                receivingStaticLabelView.setTextColor(activity.getColor(
                        viewModel.validatedAddress.label != null ? R.color.fg_significant : R.color.fg_insignificant));
            } else if (viewModel.paymentIntent.standard == null) {
                payeeGroup.setVisibility(View.VISIBLE);
                receivingStaticView.setVisibility(View.GONE);
                receivingAddressView.setVisibility(View.VISIBLE);
            } else {
                payeeGroup.setVisibility(View.GONE);
            }

            receivingAddressView.setEnabled(viewModel.state == SendCoinsViewModel.State.INPUT);

            amountGroup.setVisibility(viewModel.paymentIntent.hasAmount()
                    || (viewModel.state != null && viewModel.state.compareTo(SendCoinsViewModel.State.INPUT) >= 0)
                            ? View.VISIBLE : View.GONE);
            amountCalculatorLink.setEnabled(
                    viewModel.state == SendCoinsViewModel.State.INPUT && viewModel.paymentIntent.mayEditAmount());

            final boolean directPaymentVisible;
            if (viewModel.paymentIntent.hasPaymentUrl()) {
                if (viewModel.paymentIntent.isBluetoothPaymentUrl())
                    directPaymentVisible = bluetoothAdapter != null;
                else
                    directPaymentVisible = true;
            } else {
                directPaymentVisible = false;
            }
            directPaymentEnableView.setVisibility(directPaymentVisible ? View.VISIBLE : View.GONE);
            directPaymentEnableView.setEnabled(viewModel.state == SendCoinsViewModel.State.INPUT);

            hintView.setVisibility(View.GONE);
            if (viewModel.state == SendCoinsViewModel.State.INPUT) {
                if (blockchainState != null && blockchainState.replaying) {
                    hintView.setTextColor(activity.getColor(R.color.fg_error));
                    hintView.setVisibility(View.VISIBLE);
                    hintView.setText(R.string.send_coins_fragment_hint_replaying);
                } else if (viewModel.paymentIntent.mayEditAddress() && viewModel.validatedAddress == null
                        && !receivingAddressView.getText().toString().trim().isEmpty()) {
                    hintView.setTextColor(activity.getColor(R.color.fg_error));
                    hintView.setVisibility(View.VISIBLE);
                    hintView.setText(R.string.send_coins_fragment_receiving_address_error);
                } else if (dryrunException != null) {
                    hintView.setTextColor(activity.getColor(R.color.fg_error));
                    hintView.setVisibility(View.VISIBLE);
                    if (dryrunException instanceof DustySendRequested)
                        hintView.setText(getString(R.string.send_coins_fragment_hint_dusty_send));
                    else if (dryrunException instanceof InsufficientMoneyException)
                        hintView.setText(getString(R.string.send_coins_fragment_hint_insufficient_money,
                                btcFormat.format(((InsufficientMoneyException) dryrunException).missing)));
                    else if (dryrunException instanceof CouldNotAdjustDownwards)
                        hintView.setText(getString(R.string.send_coins_fragment_hint_empty_wallet_failed));
                    else
                        hintView.setText(dryrunException.toString());
                } else if (dryrunTransaction != null && dryrunTransaction.getFee() != null) {
                    final FeeCategory feeCategory = viewModel.feeCategory.getValue();
                    hintView.setVisibility(View.VISIBLE);
                    final int hintResId;
                    final int colorResId;
                    if (feeCategory == FeeCategory.ECONOMIC) {
                        hintResId = R.string.send_coins_fragment_hint_fee_economic;
                        colorResId = R.color.fg_less_significant;
                    } else if (feeCategory == FeeCategory.PRIORITY) {
                        hintResId = R.string.send_coins_fragment_hint_fee_priority;
                        colorResId = R.color.fg_less_significant;
                    } else {
                        hintResId = R.string.send_coins_fragment_hint_fee;
                        colorResId = R.color.fg_insignificant;
                    }
                    hintView.setTextColor(activity.getColor(colorResId));
                    hintView.setText(getString(hintResId, btcFormat.format(dryrunTransaction.getFee())));
                } else if (viewModel.paymentIntent.mayEditAddress() && viewModel.validatedAddress != null
                        && wallet != null && wallet.isAddressMine(viewModel.validatedAddress.address)) {
                    hintView.setTextColor(activity.getColor(R.color.fg_insignificant));
                    hintView.setVisibility(View.VISIBLE);
                    hintView.setText(R.string.send_coins_fragment_receiving_address_own);
                }
            }

            final Transaction sentTransaction = viewModel.sentTransaction.getValue();
            if (sentTransaction != null && wallet != null) {
                sentTransactionView.setVisibility(View.VISIBLE);
                sentTransactionViewHolder
                        .fullBind(new TransactionsAdapter.ListItem.TransactionItem(activity, sentTransaction,
                                wallet, addressBook, btcFormat, application.maxConnectedPeers()));
            } else {
                sentTransactionView.setVisibility(View.GONE);
            }

            if (viewModel.directPaymentAck != null) {
                directPaymentMessageView.setVisibility(View.VISIBLE);
                directPaymentMessageView
                        .setText(viewModel.directPaymentAck ? R.string.send_coins_fragment_direct_payment_ack
                                : R.string.send_coins_fragment_direct_payment_nack);
            } else {
                directPaymentMessageView.setVisibility(View.GONE);
            }

            final boolean viewCancelEnabled = viewModel.state != SendCoinsViewModel.State.REQUEST_PAYMENT_REQUEST
                    && viewModel.state != SendCoinsViewModel.State.DECRYPTING
                    && viewModel.state != SendCoinsViewModel.State.SIGNING;
            viewCancel.setEnabled(viewCancelEnabled);
            final boolean viewGoEnabled = everythingPlausible() && dryrunTransaction != null && wallet != null
                    && fees != null && (blockchainState == null || !blockchainState.replaying);
            viewGo.setEnabled(viewGoEnabled);

            if (viewModel.state == null || viewModel.state == SendCoinsViewModel.State.REQUEST_PAYMENT_REQUEST) {
                viewCancel.setText(R.string.button_cancel);
                viewGo.setText(null);
            } else if (viewModel.state == SendCoinsViewModel.State.INPUT) {
                viewCancel.setText(R.string.button_cancel);
                viewGo.setText(R.string.send_coins_fragment_button_send);
            } else if (viewModel.state == SendCoinsViewModel.State.DECRYPTING) {
                viewCancel.setText(R.string.button_cancel);
                viewGo.setText(R.string.send_coins_fragment_state_decrypting);
            } else if (viewModel.state == SendCoinsViewModel.State.SIGNING) {
                viewCancel.setText(R.string.button_cancel);
                viewGo.setText(R.string.send_coins_preparation_msg);
            } else if (viewModel.state == SendCoinsViewModel.State.SENDING) {
                viewCancel.setText(R.string.send_coins_fragment_button_back);
                viewGo.setText(R.string.send_coins_sending_msg);
            } else if (viewModel.state == SendCoinsViewModel.State.SENT) {
                viewCancel.setText(R.string.send_coins_fragment_button_back);
                viewGo.setText(R.string.send_coins_sent_msg);
            } else if (viewModel.state == SendCoinsViewModel.State.FAILED) {
                viewCancel.setText(R.string.send_coins_fragment_button_back);
                viewGo.setText(R.string.send_coins_failed_msg);
            }

            final boolean privateKeyPasswordViewVisible = (viewModel.state == SendCoinsViewModel.State.INPUT
                    || viewModel.state == SendCoinsViewModel.State.DECRYPTING) && wallet != null
                    && wallet.isEncrypted();
            privateKeyPasswordViewGroup.setVisibility(privateKeyPasswordViewVisible ? View.VISIBLE : View.GONE);
            privateKeyPasswordView.setEnabled(viewModel.state == SendCoinsViewModel.State.INPUT);

            // focus linking
            final int activeAmountViewId = amountCalculatorLink.activeTextView().getId();
            receivingAddressView.setNextFocusDownId(activeAmountViewId);
            if (privateKeyPasswordViewVisible)
                amountCalculatorLink.setNextFocusId(R.id.send_coins_private_key_password);
            else if (viewGoEnabled)
                amountCalculatorLink.setNextFocusId(R.id.send_coins_go);
            else if (viewCancelEnabled)
                amountCalculatorLink.setNextFocusId(R.id.send_coins_cancel);
            else
                amountCalculatorLink.setNextFocusId(View.NO_ID);
            privateKeyPasswordView.setNextFocusUpId(activeAmountViewId);
            privateKeyPasswordView.setNextFocusDownId(R.id.send_coins_go);
            viewCancel.setNextFocusUpId(
                    privateKeyPasswordViewVisible ? R.id.send_coins_private_key_password : activeAmountViewId);
            viewGo.setNextFocusUpId(
                    privateKeyPasswordViewVisible ? R.id.send_coins_private_key_password : activeAmountViewId);
        } else {
            getView().setVisibility(View.GONE);
        }
    }

    private void initStateFromIntentExtras(final Bundle extras) {
        final PaymentIntent paymentIntent = extras.getParcelable(SendCoinsActivity.INTENT_EXTRA_PAYMENT_INTENT);
        final FeeCategory feeCategory = (FeeCategory) extras
                .getSerializable(SendCoinsActivity.INTENT_EXTRA_FEE_CATEGORY);

        if (feeCategory != null) {
            log.info("got fee category {}", feeCategory);
            viewModel.feeCategory.setValue(feeCategory);
        }

        updateStateFrom(paymentIntent);
    }

    private void initStateFromBitcoinUri(final Uri bitcoinUri) {
        final String input = bitcoinUri.toString();

        new StringInputParser(input) {
            @Override
            protected void handlePaymentIntent(final PaymentIntent paymentIntent) {
                updateStateFrom(paymentIntent);
            }

            @Override
            protected void handlePrivateKey(final PrefixedChecksummedBytes key) {
                throw new UnsupportedOperationException();
            }

            @Override
            protected void handleDirectTransaction(final Transaction transaction) throws VerificationException {
                throw new UnsupportedOperationException();
            }

            @Override
            protected void error(final int messageResId, final Object... messageArgs) {
                final DialogBuilder dialog = DialogBuilder.dialog(activity, 0, messageResId, messageArgs);
                dialog.singleDismissButton(activityDismissListener);
                dialog.show();
            }
        }.parse();
    }

    private void initStateFromPaymentRequest(final String mimeType, final byte[] input) {
        new BinaryInputParser(mimeType, input) {
            @Override
            protected void handlePaymentIntent(final PaymentIntent paymentIntent) {
                updateStateFrom(paymentIntent);
            }

            @Override
            protected void error(final int messageResId, final Object... messageArgs) {
                final DialogBuilder dialog = DialogBuilder.dialog(activity, 0, messageResId, messageArgs);
                dialog.singleDismissButton(activityDismissListener);
                dialog.show();
            }
        }.parse();
    }

    private void initStateFromIntentUri(final String mimeType, final Uri bitcoinUri) {
        try {
            final InputStream is = contentResolver.openInputStream(bitcoinUri);

            new StreamInputParser(mimeType, is) {
                @Override
                protected void handlePaymentIntent(final PaymentIntent paymentIntent) {
                    updateStateFrom(paymentIntent);
                }

                @Override
                protected void error(final int messageResId, final Object... messageArgs) {
                    final DialogBuilder dialog = DialogBuilder.dialog(activity, 0, messageResId, messageArgs);
                    dialog.singleDismissButton(activityDismissListener);
                    dialog.show();
                }
            }.parse();
        } catch (final FileNotFoundException x) {
            throw new RuntimeException(x);
        }
    }

    private void updateStateFrom(final PaymentIntent paymentIntent) {
        log.info("got {}", paymentIntent);

        viewModel.paymentIntent = paymentIntent;

        viewModel.validatedAddress = null;
        viewModel.directPaymentAck = null;

        // delay these actions until fragment is resumed
        handler.post(() -> {
            if (paymentIntent.hasPaymentRequestUrl() && paymentIntent.isBluetoothPaymentRequestUrl()) {
                if (bluetoothAdapter.isEnabled())
                    requestPaymentRequest();
                else
                    // ask for permission to enable bluetooth
                    requestEnableBluetoothForPaymentRequestLauncher.launch(null);
            } else if (paymentIntent.hasPaymentRequestUrl() && paymentIntent.isHttpPaymentRequestUrl()) {
                requestPaymentRequest();
            } else {
                setState(SendCoinsViewModel.State.INPUT);

                receivingAddressView.setText(null);
                amountCalculatorLink.setBtcAmount(paymentIntent.getAmount());
                viewModel.amount.setValue(paymentIntent.getAmount());

                if (paymentIntent.isBluetoothPaymentUrl())
                    directPaymentEnableView.setChecked(bluetoothAdapter != null && bluetoothAdapter.isEnabled() && checkBluetoothConnectPermission());
                else if (paymentIntent.isHttpPaymentUrl())
                    directPaymentEnableView.setChecked(true);

                requestFocusFirst();
                updateView();
                viewModel.maybeDryrun();
            }
        });
    }

    private boolean checkBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ContextCompat.checkSelfPermission(activity,
                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void maybeEnableBluetooth() {
        if (!checkBluetoothConnectPermission()) {
            log.info("missing {}, requesting", Manifest.permission.BLUETOOTH_CONNECT);
            requestPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (!bluetoothAdapter.isEnabled()) {
            log.info("bluetooth disabled, requesting to enable");
            requestEnableBluetoothForDirectPaymentLauncher.launch(null);
        } else {
            log.info("bluetooth enabled, ready to connect");
        }
    }

    private void requestPaymentRequest() {
        final String paymentRequestHost;
        if (!Bluetooth.isBluetoothUrl(viewModel.paymentIntent.paymentRequestUrl))
            paymentRequestHost = Uri.parse(viewModel.paymentIntent.paymentRequestUrl).getHost();
        else
            paymentRequestHost = Bluetooth
                    .decompressMac(Bluetooth.getBluetoothMac(viewModel.paymentIntent.paymentRequestUrl));

        viewModel.progress
                .setValue(getString(R.string.send_coins_fragment_request_payment_request_progress, paymentRequestHost));
        setState(SendCoinsViewModel.State.REQUEST_PAYMENT_REQUEST);

        final RequestPaymentRequestTask.ResultCallback callback = new RequestPaymentRequestTask.ResultCallback() {
            @Override
            public void onPaymentIntent(final PaymentIntent paymentIntent) {
                viewModel.progress.setValue(null);

                if (viewModel.paymentIntent.isExtendedBy(paymentIntent)) {
                    // success
                    setState(SendCoinsViewModel.State.INPUT);
                    updateStateFrom(paymentIntent);
                    updateView();
                } else {
                    final List<String> reasons = new LinkedList<>();
                    if (!viewModel.paymentIntent.equalsAddress(paymentIntent))
                        reasons.add("address");
                    if (!viewModel.paymentIntent.equalsAmount(paymentIntent))
                        reasons.add("amount");
                    if (reasons.isEmpty())
                        reasons.add("unknown");

                    final DialogBuilder dialog = DialogBuilder.warn(activity,
                            R.string.send_coins_fragment_request_payment_request_failed_title,
                            R.string.send_coins_fragment_request_payment_request_failed_message,
                            paymentRequestHost, Joiner.on(", ").join(reasons));
                    dialog.singleDismissButton((d, which) -> handleCancel());
                    dialog.show();

                    log.info("BIP72 trust check failed: {}", reasons);
                }
            }

            @Override
            public void onFail(final int messageResId, final Object... messageArgs) {
                viewModel.progress.setValue(null);

                final DialogBuilder dialog = DialogBuilder.warn(activity,
                        R.string.send_coins_fragment_request_payment_request_failed_title, messageResId, messageArgs);
                dialog.setPositiveButton(R.string.button_retry, (d, which) -> requestPaymentRequest());
                dialog.setNegativeButton(R.string.button_dismiss, (d, which) -> {
                    if (!viewModel.paymentIntent.hasOutputs())
                        handleCancel();
                    else
                        setState(SendCoinsViewModel.State.INPUT);
                });
                dialog.show();
            }
        };

        if (!Bluetooth.isBluetoothUrl(viewModel.paymentIntent.paymentRequestUrl))
            new RequestPaymentRequestTask.HttpRequestTask(backgroundHandler, callback, application.httpUserAgent())
                    .requestPaymentRequest(viewModel.paymentIntent.paymentRequestUrl);
        else
            new RequestPaymentRequestTask.BluetoothRequestTask(backgroundHandler, callback, bluetoothAdapter)
                    .requestPaymentRequest(viewModel.paymentIntent.paymentRequestUrl);
    }

    // from BitcoinIntegration.java:

    private static final String INTENT_EXTRA_PAYMENTREQUEST = "paymentrequest";
    private static final String INTENT_EXTRA_PAYMENT = "payment";
    private static final String INTENT_EXTRA_TRANSACTION_HASH = "transaction_hash";

    /**
     * Get payment request from intent. Meant for usage by applications accepting payment requests.
     *
     * @param intent intent
     * @return payment request or null
     */
    private static byte[] paymentRequestFromIntent(final Intent intent) {
        return intent.getByteArrayExtra(INTENT_EXTRA_PAYMENTREQUEST);
    }

    /**
     * Put BIP70 payment message into result intent. Meant for usage by Bitcoin wallet applications.
     *
     * @param result  result intent
     * @param payment payment message
     */
    private static void paymentToResult(final Intent result, final byte[] payment) {
        result.putExtra(INTENT_EXTRA_PAYMENT, payment);
    }

    /**
     * Put transaction hash into result intent. Meant for usage by Bitcoin wallet applications.
     *
     * @param result result intent
     * @param txHash transaction hash
     */
    private static void transactionHashToResult(final Intent result, final String txHash) {
        result.putExtra(INTENT_EXTRA_TRANSACTION_HASH, txHash);
    }
}
