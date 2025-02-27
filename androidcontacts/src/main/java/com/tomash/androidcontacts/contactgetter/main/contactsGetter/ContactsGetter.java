package com.tomash.androidcontacts.contactgetter.main.contactsGetter;

import static android.provider.ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE;
import static android.provider.ContactsContract.CommonDataKinds.Organization.DEPARTMENT;
import static android.provider.ContactsContract.CommonDataKinds.Organization.TITLE;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.CommonDataKinds.Im;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.SipAddress;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.util.SparseArray;

import com.tomash.androidcontacts.contactgetter.entity.Address;
import com.tomash.androidcontacts.contactgetter.entity.ContactData;
import com.tomash.androidcontacts.contactgetter.entity.Email;
import com.tomash.androidcontacts.contactgetter.entity.Group;
import com.tomash.androidcontacts.contactgetter.entity.IMAddress;
import com.tomash.androidcontacts.contactgetter.entity.NameData;
import com.tomash.androidcontacts.contactgetter.entity.Organization;
import com.tomash.androidcontacts.contactgetter.entity.PhoneNumber;
import com.tomash.androidcontacts.contactgetter.entity.Relation;
import com.tomash.androidcontacts.contactgetter.entity.SpecialDate;
import com.tomash.androidcontacts.contactgetter.interfaces.WithLabel;
import com.tomash.androidcontacts.contactgetter.main.FieldType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

class ContactsGetter {
    private final ContentResolver mResolver;
    private final Context mCtx;
    private final List<FieldType> mEnabledFields;
    private final String[] mSelectionArgs;
    private final String mSorting;
    private final String mSelection;
    private static final String MAIN_DATA_KEY = "data1";
    private static final String LABEL_DATA_KEY = "data2";
    private static final String CUSTOM_LABEL_DATA_KEY = "data3";
    private static final String ID_KEY = "contact_id";
    private static final String[] WITH_LABEL_PROJECTION = new String[]{ID_KEY, MAIN_DATA_KEY, LABEL_DATA_KEY, CUSTOM_LABEL_DATA_KEY};
    private static final String[] CONTACTS_PROJECTION = new String[]{ContactsContract.Contacts._ID, ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
            ContactsContract.Contacts.PHOTO_URI, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.STARRED};
    private static final String[] CONTACTS_PROJECTION_LOW_API = new String[]{ContactsContract.Contacts._ID,
            ContactsContract.Contacts.PHOTO_URI, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.STARRED};
    private static final String[] ADDITIONAL_DATA_PROJECTION = new String[]{ContactsContract.Contacts._ID,
            ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.CONTACT_ID};
    private Class<? extends ContactData> mContactDataClass;

    ContactsGetter(Context ctx, List<FieldType> enabledFields, String sorting, String[] selectionArgs, String selection) {
        this.mCtx = ctx;
        this.mResolver = ctx.getContentResolver();
        this.mEnabledFields = enabledFields;
        this.mSelectionArgs = selectionArgs;
        this.mSorting = sorting;
        this.mSelection = selection;
    }

    ContactsGetter setContactDataClass(Class<? extends ContactData> mContactDataClass) {
        this.mContactDataClass = mContactDataClass;
        return this;
    }

    private Cursor getContactsCursorWithSelection(String ordering, String selection, String[] selectionArgs) {
        return mResolver.query(ContactsContract.Contacts.CONTENT_URI,
                CONTACTS_PROJECTION, selection, selectionArgs, ordering);
    }

    //./adb shell content query --uri content://com.android.contacts/deleted_contacts > deleted_contacts.txt
    private static final String[] DELETED_PROJECTION = new String[]{ContactsContract.DeletedContacts.CONTACT_ID};

    private Cursor getDeletedContactsCursorWithSelection(long since) {
        return mResolver.query(
                ContactsContract.DeletedContacts.CONTENT_URI,
                DELETED_PROJECTION,
                String.format(Locale.ENGLISH, "%s >= %d", ContactsContract.DeletedContacts.CONTACT_DELETED_TIMESTAMP, since),
                null,
                ContactsContract.DeletedContacts.CONTACT_ID
        );

    }

    private Cursor getContactsCursorWithAdditionalData() {
        return mResolver.query(ContactsContract.RawContacts.CONTENT_URI, ADDITIONAL_DATA_PROJECTION, null, null, null);
    }

    private <T extends ContactData> T getContactData() {
        if (mContactDataClass == null) {
            return (T) new ContactData() {
            };
        }
        try {
            return (T) mContactDataClass.getConstructor().newInstance();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    <T extends ContactData> List<T> getDeletedContacts(long since) {
        Cursor deletedUsers = getDeletedContactsCursorWithSelection(since);
        List<T> result = new ArrayList<>();
        if (deletedUsers == null)
            return result;
        int position = deletedUsers.getColumnIndex(ContactsContract.DeletedContacts.CONTACT_ID);

        if (position < 0) throw new RuntimeException("NO DELETED CONTACT ID COLUMN");

        while (deletedUsers.moveToNext()) {
            int id = deletedUsers.getInt(position);
            result.add((T) new ContactData(id) {
            });
        }
        return result;
    }

    <T extends ContactData> List<T> getContacts() {
        Cursor mainCursor = getContactsCursorWithSelection(mSorting, mSelection, mSelectionArgs);
        Cursor additionalDataCursor = getContactsCursorWithAdditionalData();
        SparseArray<T> contactsSparse = new SparseArray<>();
        List<T> result = new ArrayList<>();
        if (mainCursor == null)
            return result;
        SparseArray<List<PhoneNumber>> phonesDataMap = mEnabledFields.contains(FieldType.PHONE_NUMBERS) ? getPhoneNumberMap() : new SparseArray<List<PhoneNumber>>();
        SparseArray<List<Address>> addressDataMap = mEnabledFields.contains(FieldType.ADDRESS) ? getDataMap(getCursorFromContentType(WITH_LABEL_PROJECTION, StructuredPostal.CONTENT_ITEM_TYPE), new WithLabelCreator<Address>() {
            @Override
            public Address create(String mainData, int contactId, int labelId, String labelName) {
                Address address = new Address(mCtx, mainData, labelId);
                address.setContactId(contactId);
                return address;
            }
        }) : new SparseArray<List<Address>>();
        SparseArray<List<Email>> emailDataMap = mEnabledFields.contains(FieldType.EMAILS) ? getDataMap(getCursorFromContentType(WITH_LABEL_PROJECTION, CommonDataKinds.Email.CONTENT_ITEM_TYPE), new WithLabelCreator<Email>() {
            @Override
            public Email create(String mainData, int contactId, int labelId, String labelName) {
                Email email;
                if (labelName != null)
                    email = new Email(mainData, labelName);
                else
                    email = new Email(mCtx, mainData, labelId);
                email.setContactId(contactId);
                return email;
            }
        }) : new SparseArray<List<Email>>();
        SparseArray<List<SpecialDate>> specialDateMap = mEnabledFields.contains(FieldType.SPECIAL_DATES) ? getDataMap(getCursorFromContentType(WITH_LABEL_PROJECTION, Event.CONTENT_ITEM_TYPE), new WithLabelCreator<SpecialDate>() {
            @Override
            public SpecialDate create(String mainData, int contactId, int labelId, String labelName) {
                SpecialDate specialData;
                if (labelName != null)
                    specialData = new SpecialDate(mainData, labelName);
                else
                    specialData = new SpecialDate(mCtx, mainData, labelId);
                specialData.setContactId(contactId);
                return specialData;
            }
        }) : new SparseArray<List<SpecialDate>>();
        SparseArray<List<Relation>> relationMap = mEnabledFields.contains(FieldType.RELATIONS) ? getDataMap(getCursorFromContentType(WITH_LABEL_PROJECTION, CommonDataKinds.Relation.CONTENT_ITEM_TYPE), new WithLabelCreator<Relation>() {
            @Override
            public Relation create(String mainData, int contactId, int labelId, String labelName) {
                Relation relation;
                if (labelName != null)
                    relation = new Relation(mainData, labelName);
                else
                    relation = new Relation(mCtx, mainData, labelId);
                relation.setContactId(contactId);
                return relation;
            }
        }) : new SparseArray<List<Relation>>();
        SparseArray<List<IMAddress>> imAddressesDataMap = mEnabledFields.contains(FieldType.IM_ADDRESSES) ? getIMAddressesMap() : new SparseArray<>();
        SparseArray<List<String>> websitesDataMap = mEnabledFields.contains(FieldType.WEBSITES) ? getWebSitesMap() : new SparseArray<>();
        SparseArray<String> notesDataMap = mEnabledFields.contains(FieldType.NOTES) ? getStringDataMap(Note.CONTENT_ITEM_TYPE) : new SparseArray<>();
        SparseArray<String> nicknameDataMap = mEnabledFields.contains(FieldType.NICKNAME) ? getStringDataMap(Nickname.CONTENT_ITEM_TYPE) : new SparseArray<>();
        SparseArray<String> sipDataMap = mEnabledFields.contains(FieldType.SIP) ? getStringDataMap(SipAddress.CONTENT_ITEM_TYPE) : new SparseArray<>();
        SparseArray<Organization> organisationDataMap = mEnabledFields.contains(FieldType.ORGANIZATION) ? getOrganizationDataMap() : new SparseArray<>();
        SparseArray<NameData> nameDataMap = mEnabledFields.contains(FieldType.NAME_DATA) ? getNameDataMap() : new SparseArray<>();
        SparseArray<List<Group>> groupsDataMap = mEnabledFields.contains(FieldType.GROUPS) ? getGroupsDataMap() : new SparseArray<>();

        int ID_IDX = mainCursor.getColumnIndex(ContactsContract.Contacts._ID);
        int CONTACT_LAST_UPDATED_TIMESTAMP_IDX = mainCursor.getColumnIndex(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP);
        int PHOTO_URI_IDX = mainCursor.getColumnIndex(ContactsContract.Contacts.PHOTO_URI);
        int LOOKUP_KEY_IDX = mainCursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY);
        int STARRED_IDX = mainCursor.getColumnIndex(ContactsContract.Contacts.STARRED);
        int DISPLAY_NAME_IDX = mainCursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME);
        if (isIndexNegative(ID_IDX, CONTACT_LAST_UPDATED_TIMESTAMP_IDX, PHOTO_URI_IDX, LOOKUP_KEY_IDX, STARRED_IDX, DISPLAY_NAME_IDX)) {
            mainCursor.close();
            return result;
        }
        while (mainCursor.moveToNext()) {
            int id = mainCursor.getInt(ID_IDX);
            long date = mainCursor.getLong(CONTACT_LAST_UPDATED_TIMESTAMP_IDX);
            String photoUriString = mainCursor.getString(PHOTO_URI_IDX);
            String lookupKey = mainCursor.getString(LOOKUP_KEY_IDX);
            boolean isFavorite = mainCursor.getInt(STARRED_IDX) == 1;
            Uri photoUri = photoUriString == null ? Uri.EMPTY : Uri.parse(photoUriString);
            T data = (T) getContactData()
                    .setContactId(id)
                    .setLookupKey(lookupKey)
                    .setLastModificationDate(date)
                    .setPhoneList(phonesDataMap.get(id))
                    .setAddressesList(addressDataMap.get(id))
                    .setEmailList(emailDataMap.get(id))
                    .setWebsitesList(websitesDataMap.get(id))
                    .setNote(notesDataMap.get(id))
                    .setImAddressesList(imAddressesDataMap.get(id))
                    .setRelationsList(relationMap.get(id))
                    .setSpecialDatesList(specialDateMap.get(id))
                    .setNickName(nicknameDataMap.get(id))
                    .setOrganization(organisationDataMap.get(id))
                    .setSipAddress(sipDataMap.get(id))
                    .setNameData(nameDataMap.get(id))
                    .setPhotoUri(photoUri)
                    .setFavorite(isFavorite)
                    .setGroupList(groupsDataMap.get(id))
                    .setCompositeName(mainCursor.getString(DISPLAY_NAME_IDX));
            contactsSparse.put(id, data);
            result.add(data);
        }
        mainCursor.close();
        int CONTACT_ID_IDX = additionalDataCursor.getColumnIndex(ContactsContract.RawContacts.CONTACT_ID);
        int ACCOUNT_TYPE_IDX = additionalDataCursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE);
        int ACCOUNT_NAME_IDX = additionalDataCursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME);

        if (!isIndexNegative(ACCOUNT_NAME_IDX, ACCOUNT_TYPE_IDX, CONTACT_ID_IDX)) {
            while (additionalDataCursor.moveToNext()) {
                int id = additionalDataCursor.getInt(CONTACT_ID_IDX);
                if (id >= 0) {
                    ContactData relatedContactData = contactsSparse.get(id);
                    if (relatedContactData != null) {
                        String accountType = additionalDataCursor.getString(ACCOUNT_TYPE_IDX);
                        String accountName = additionalDataCursor.getString(ACCOUNT_NAME_IDX);
                        relatedContactData.setAccountName(accountName)
                                .setAccountType(accountType);
                    }
                }
            }
        }

        additionalDataCursor.close();
        return result;
    }


    private SparseArray<List<String>> getWebSitesMap() {
        SparseArray<List<String>> idSiteMap = new SparseArray<>();
        Cursor websiteCur = getCursorFromContentType(new String[]{ID_KEY, MAIN_DATA_KEY}, Website.CONTENT_ITEM_TYPE);
        if (websiteCur != null) {
            int ID_KEY_IDX = websiteCur.getColumnIndex(ID_KEY);
            int MAIN_DATA_KEY_IDX = websiteCur.getColumnIndex(MAIN_DATA_KEY);
            if (isIndexNegative(ID_KEY_IDX, MAIN_DATA_KEY_IDX)) {
                websiteCur.close();
                return idSiteMap;
            }

            while (websiteCur.moveToNext()) {
                int id = websiteCur.getInt(ID_KEY_IDX);
                String website = websiteCur.getString(MAIN_DATA_KEY_IDX);
                List<String> currentWebsiteList = idSiteMap.get(id);
                if (currentWebsiteList == null) {
                    currentWebsiteList = new ArrayList<>();
                    currentWebsiteList.add(website);
                    idSiteMap.put(id, currentWebsiteList);
                } else currentWebsiteList.add(website);
            }
            websiteCur.close();
        }
        return idSiteMap;
    }

    private SparseArray<Group> getGroupsMap() {
        SparseArray<Group> idGroupMap = new SparseArray<>();
        Cursor groupCursor = mResolver.query(
                ContactsContract.Groups.CONTENT_URI,
                new String[]{
                        ContactsContract.Groups._ID,
                        ContactsContract.Groups.TITLE
                }, null, null, null
        );
        if (groupCursor != null) {
            int ID_IDX = groupCursor.getColumnIndex(ContactsContract.Groups._ID);
            int TITLE_IDX = groupCursor.getColumnIndex(ContactsContract.Groups.TITLE);

            if (isIndexNegative(ID_IDX, TITLE_IDX)) {
                groupCursor.close();
                return idGroupMap;
            }

            while (groupCursor.moveToNext()) {
                int id = groupCursor.getInt(ID_IDX);
                String title = groupCursor.getString(TITLE_IDX);
                idGroupMap.put(id, new Group()
                        .setGroupId(id)
                        .setGroupTitle(title));
            }
            groupCursor.close();
        }
        return idGroupMap;
    }

    private SparseArray<List<Group>> getGroupsDataMap() {
        SparseArray<List<Group>> idListGroupMap = new SparseArray<>();
        SparseArray<Group> groupMapById = getGroupsMap();
        Cursor groupMembershipCursor = getCursorFromContentType(new String[]{ID_KEY, MAIN_DATA_KEY}, GroupMembership.CONTENT_ITEM_TYPE);
        if (groupMembershipCursor != null) {
            int ID_KEY_IDX = groupMembershipCursor.getColumnIndex(ID_KEY);
            int MAIN_DATA_KEY_IDX = groupMembershipCursor.getColumnIndex(MAIN_DATA_KEY);
            if (isIndexNegative(ID_KEY_IDX, MAIN_DATA_KEY_IDX)) {
                groupMembershipCursor.close();
                return idListGroupMap;
            }
            while (groupMembershipCursor.moveToNext()) {
                int id = groupMembershipCursor.getInt(ID_KEY_IDX);
                int groupId = groupMembershipCursor.getInt(MAIN_DATA_KEY_IDX);
                List<Group> currentIdGroupList = idListGroupMap.get(id);
                if (currentIdGroupList == null) {
                    currentIdGroupList = new ArrayList<>();
                    currentIdGroupList.add(groupMapById.get(groupId));
                    idListGroupMap.put(id, currentIdGroupList);
                } else
                    currentIdGroupList.add(groupMapById.get(groupId));
            }
            groupMembershipCursor.close();
        }
        return idListGroupMap;
    }


    private SparseArray<NameData> getNameDataMap() {
        Cursor nameCursor = getCursorFromContentType(new String[]{ID_KEY, StructuredName.DISPLAY_NAME, StructuredName.GIVEN_NAME, StructuredName.PHONETIC_MIDDLE_NAME, StructuredName.PHONETIC_FAMILY_NAME,
                StructuredName.FAMILY_NAME, StructuredName.PREFIX, StructuredName.MIDDLE_NAME, StructuredName.SUFFIX, StructuredName.PHONETIC_GIVEN_NAME}, StructuredName.CONTENT_ITEM_TYPE);
        SparseArray<NameData> nameDataSparseArray = new SparseArray<>();
        if (nameCursor != null) {
            int ID_KEY_INDEX = nameCursor.getColumnIndex(ID_KEY);
            int DISPLAY_NAME_INDEX = nameCursor.getColumnIndex(StructuredName.DISPLAY_NAME);
            int GIVEN_NAME_INDEX = nameCursor.getColumnIndex(StructuredName.GIVEN_NAME);
            int FAMILY_NAME_INDEX = nameCursor.getColumnIndex(StructuredName.FAMILY_NAME);
            int PREFIX_INDEX = nameCursor.getColumnIndex(StructuredName.PREFIX);
            int MIDDLE_NAME_INDEX = nameCursor.getColumnIndex(StructuredName.MIDDLE_NAME);
            int SUFFIX_INDEX = nameCursor.getColumnIndex(StructuredName.SUFFIX);
            int PHONETIC_GIVEN_NAME_INDEX = nameCursor.getColumnIndex(StructuredName.PHONETIC_GIVEN_NAME);
            int PHONETIC_MIDDLE_NAME_INDEX = nameCursor.getColumnIndex(StructuredName.PHONETIC_MIDDLE_NAME);
            int PHONETIC_FAMILY_NAME_INDEX = nameCursor.getColumnIndex(StructuredName.PHONETIC_FAMILY_NAME);
            if (
                    isIndexNegative(ID_KEY_INDEX, DISPLAY_NAME_INDEX, GIVEN_NAME_INDEX, FAMILY_NAME_INDEX, PREFIX_INDEX,
                            MIDDLE_NAME_INDEX, SUFFIX_INDEX, PHONETIC_GIVEN_NAME_INDEX, PHONETIC_MIDDLE_NAME_INDEX, PHONETIC_FAMILY_NAME_INDEX)
            ) {
                nameCursor.close();
                return nameDataSparseArray;
            }

            while (nameCursor.moveToNext()) {
                int id = nameCursor.getInt(ID_KEY_INDEX);
                if (nameDataSparseArray.get(id) == null)
                    nameDataSparseArray.put(id, new NameData()
                            .setFullName(nameCursor.getString(DISPLAY_NAME_INDEX))
                            .setFirstName(nameCursor.getString(GIVEN_NAME_INDEX))
                            .setSurname(nameCursor.getString(FAMILY_NAME_INDEX))
                            .setNamePrefix(nameCursor.getString(PREFIX_INDEX))
                            .setMiddleName(nameCursor.getString(MIDDLE_NAME_INDEX))
                            .setNameSuffix(nameCursor.getString(SUFFIX_INDEX))
                            .setPhoneticFirst(nameCursor.getString(PHONETIC_GIVEN_NAME_INDEX))
                            .setPhoneticMiddle(nameCursor.getString(PHONETIC_MIDDLE_NAME_INDEX))
                            .setPhoneticLast(nameCursor.getString(PHONETIC_FAMILY_NAME_INDEX))
                    );
            }
            nameCursor.close();
        }


        return nameDataSparseArray;
    }

    private SparseArray<List<IMAddress>> getIMAddressesMap() {
        SparseArray<List<IMAddress>> idImAddressMap = new SparseArray<>();
        Cursor cur = getCursorFromContentType(new String[]{ID_KEY, MAIN_DATA_KEY, Im.PROTOCOL, Im.CUSTOM_PROTOCOL}, Im.CONTENT_ITEM_TYPE);
        if (cur != null) {
            int ID_KEY_INDEX = cur.getColumnIndex(ID_KEY);
            int MAIN_DATA_KEY_INDEX = cur.getColumnIndex(MAIN_DATA_KEY);
            int PROTOCOL_INDEX = cur.getColumnIndex(Im.PROTOCOL);
            int CUSTOM_PROTOCOL_INDEX = cur.getColumnIndex(Im.CUSTOM_PROTOCOL);
            if (isIndexNegative(ID_KEY_INDEX, MAIN_DATA_KEY_INDEX, PROTOCOL_INDEX, CUSTOM_PROTOCOL_INDEX)) {
                cur.close();
                return idImAddressMap;
            }
            while (cur.moveToNext()) {
                int id = cur.getInt(ID_KEY_INDEX);
                String data = cur.getString(MAIN_DATA_KEY_INDEX);
                int labelId = cur.getInt(PROTOCOL_INDEX);
                String customLabel = cur.getString(CUSTOM_PROTOCOL_INDEX);
                IMAddress current;
                if (customLabel == null)
                    current = new IMAddress(mCtx, data, labelId);
                else
                    current = new IMAddress(data, customLabel);
                List<IMAddress> currentWebsiteList = idImAddressMap.get(id);
                if (currentWebsiteList == null) {
                    currentWebsiteList = new ArrayList<>();
                    currentWebsiteList.add(current);
                    idImAddressMap.put(id, currentWebsiteList);
                } else currentWebsiteList.add(current);
            }
            cur.close();
        }
        return idImAddressMap;
    }

    private SparseArray<List<PhoneNumber>> getPhoneNumberMap() {
        Cursor phoneCursor = getCursorFromContentType(new String[]{ID_KEY, MAIN_DATA_KEY, LABEL_DATA_KEY, CUSTOM_LABEL_DATA_KEY, ContactsContract.Data.IS_PRIMARY}, Phone.CONTENT_ITEM_TYPE);
        SparseArray<List<PhoneNumber>> dataSparseArray = new SparseArray<>();
        if (phoneCursor != null) {
            int ID_KEY_INDEX = phoneCursor.getColumnIndex(ID_KEY);
            int MAIN_DATA_KEY_INDEX = phoneCursor.getColumnIndex(MAIN_DATA_KEY);
            int LABEL_DATA_KEY_INDEX = phoneCursor.getColumnIndex(LABEL_DATA_KEY);
            int IS_PRIMARY_INDEX = phoneCursor.getColumnIndex(ContactsContract.Data.IS_PRIMARY);
            if (isIndexNegative(ID_KEY_INDEX, MAIN_DATA_KEY_INDEX, LABEL_DATA_KEY_INDEX, IS_PRIMARY_INDEX)) {
                phoneCursor.close();
                return dataSparseArray;
            }
            while (phoneCursor.moveToNext()) {
                int id = phoneCursor.getInt(ID_KEY_INDEX);
                String data = phoneCursor.getString(MAIN_DATA_KEY_INDEX);
                int labelId = phoneCursor.getInt(LABEL_DATA_KEY_INDEX);
                boolean isPrimary = phoneCursor.getInt(IS_PRIMARY_INDEX) == 1;
                PhoneNumber number;
                number = new PhoneNumber(mCtx, data, labelId);
                number.setContactId(id);
                number.setPrimary(isPrimary);
                List<PhoneNumber> currentDataList = dataSparseArray.get(id);
                if (currentDataList == null) {
                    currentDataList = new ArrayList<>();
                    currentDataList.add(number);
                    dataSparseArray.put(id, currentDataList);
                } else currentDataList.add(number);
            }
            phoneCursor.close();
        }
        return dataSparseArray;
    }

    private SparseArray<String> getStringDataMap(String contentType) {
        SparseArray<String> idNoteMap = new SparseArray<>();
        Cursor noteCur = getCursorFromContentType(new String[]{ID_KEY, MAIN_DATA_KEY}, contentType);
        if (noteCur != null) {
            int ID_KEY_INDEX = noteCur.getColumnIndex(ID_KEY);
            int MAIN_DATA_KEY_INDEX = noteCur.getColumnIndex(MAIN_DATA_KEY);
            if (isIndexNegative(ID_KEY_INDEX, MAIN_DATA_KEY_INDEX)) {
                noteCur.close();
                return idNoteMap;
            }
            while (noteCur.moveToNext()) {
                int id = noteCur.getInt(ID_KEY_INDEX);
                String note = noteCur.getString(MAIN_DATA_KEY_INDEX);
                if (note != null) idNoteMap.put(id, note);
            }
            noteCur.close();
        }
        return idNoteMap;
    }

    private SparseArray<Organization> getOrganizationDataMap() {
        SparseArray<Organization> idOrganizationMap = new SparseArray<>();
        Cursor noteCur = getCursorFromContentType(new String[]{ID_KEY, MAIN_DATA_KEY, TITLE, DEPARTMENT}, CONTENT_ITEM_TYPE);
        if (noteCur != null) {
            int ID_KEY_INDEX = noteCur.getColumnIndex(ID_KEY);
            int MAIN_DATA_KEY_INDEX = noteCur.getColumnIndex(MAIN_DATA_KEY);
            int TITLE_INDEX = noteCur.getColumnIndex(TITLE);
            int DEPARTMENT_INDEX = noteCur.getColumnIndex(DEPARTMENT);
            if (isIndexNegative(ID_KEY_INDEX, MAIN_DATA_KEY_INDEX, TITLE_INDEX, DEPARTMENT_INDEX)) {
                noteCur.close();
                return idOrganizationMap;
            }
            while (noteCur.moveToNext()) {
                int id = noteCur.getInt(ID_KEY_INDEX);
                String organizationName = noteCur.getString(MAIN_DATA_KEY_INDEX);
                String organizationTitle = noteCur.getString(TITLE_INDEX);
                String organizationDepartment = noteCur.getString(DEPARTMENT_INDEX);
                idOrganizationMap.put(id, new Organization()
                        .setName(organizationName)
                        .setTitle(organizationTitle)
                        .setDepartment(organizationDepartment));
            }
            noteCur.close();
        }
        return idOrganizationMap;
    }


    private <T extends WithLabel> SparseArray<List<T>> getDataMap(Cursor dataCursor, WithLabelCreator<T> creator) {
        SparseArray<List<T>> dataSparseArray = new SparseArray<>();
        if (dataCursor != null) {
            int ID_KEY_INDEX = dataCursor.getColumnIndex(ID_KEY);
            int MAIN_DATA_KEY_INDEX = dataCursor.getColumnIndex(MAIN_DATA_KEY);
            int LABEL_DATA_KEY_INDEX = dataCursor.getColumnIndex(LABEL_DATA_KEY);
            int CUSTOM_LABEL_DATA_KEY_INDEX = dataCursor.getColumnIndex(CUSTOM_LABEL_DATA_KEY);

            if (isIndexNegative(ID_KEY_INDEX, MAIN_DATA_KEY_INDEX, LABEL_DATA_KEY_INDEX, CUSTOM_LABEL_DATA_KEY_INDEX)) {
                dataCursor.close();
                return dataSparseArray;
            }

            while (dataCursor.moveToNext()) {
                int id = dataCursor.getInt(ID_KEY_INDEX);
                String data = dataCursor.getString(MAIN_DATA_KEY_INDEX);
                int labelId = dataCursor.getInt(LABEL_DATA_KEY_INDEX);
                String customLabel = dataCursor.getString(CUSTOM_LABEL_DATA_KEY_INDEX);
                T current = creator.create(data, id, labelId, customLabel);
                List<T> currentDataList = dataSparseArray.get(id);
                if (currentDataList == null) {
                    currentDataList = new ArrayList<>();
                    currentDataList.add(current);
                    dataSparseArray.put(id, currentDataList);
                } else currentDataList.add(current);
            }
            dataCursor.close();
        }
        return dataSparseArray;
    }

    private Cursor getCursorFromContentType(String[] projection, String contentType) {
        String orgWhere = ContactsContract.Data.MIMETYPE + " = ?";
        String[] orgWhereParams = new String[]{contentType};
        return mResolver.query(ContactsContract.Data.CONTENT_URI,
                projection, orgWhere, orgWhereParams, null);
    }

    interface WithLabelCreator<T extends WithLabel> {
        T create(String mainData, int contactId, int labelId, String labelName);
    }

    private Boolean isIndexNegative(int... index) {
        return Arrays.stream(index).anyMatch(idx -> idx == -1);
    }
}