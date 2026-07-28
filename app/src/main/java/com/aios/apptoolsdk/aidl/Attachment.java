package com.aios.apptoolsdk.aidl;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

public final class Attachment implements Parcelable {
    public static final Creator<Attachment> CREATOR = new Creator<Attachment>() {
        @Override
        public Attachment createFromParcel(Parcel source) {
            return new Attachment(source);
        }

        @Override
        public Attachment[] newArray(int size) {
            return new Attachment[size];
        }
    };

    public final String name;
    public final String mimeType;
    public final ParcelFileDescriptor fd;
    public final String uri;

    private Attachment(
            String name,
            String mimeType,
            ParcelFileDescriptor fd,
            String uri
    ) {
        this.name = name;
        this.mimeType = mimeType;
        this.fd = fd;
        this.uri = uri;
    }

    private Attachment(Parcel source) {
        name = source.readString();
        mimeType = source.readString();
        fd = source.readInt() == 1
                ? source.readParcelable(ParcelFileDescriptor.class.getClassLoader())
                : null;
        uri = source.readString();
    }

    public static Attachment fromFd(
            String name,
            String mimeType,
            ParcelFileDescriptor fd
    ) {
        return new Attachment(name, mimeType, fd, null);
    }

    @Override
    public int describeContents() {
        return fd != null ? CONTENTS_FILE_DESCRIPTOR : 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeString(mimeType);
        if (fd != null) {
            dest.writeInt(1);
            dest.writeParcelable(fd, flags);
        } else {
            dest.writeInt(0);
        }
        dest.writeString(uri);
    }
}
