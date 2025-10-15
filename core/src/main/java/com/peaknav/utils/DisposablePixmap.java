package com.peaknav.utils;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class DisposablePixmap {
    private final Pixmap pm;
    private final ReadWriteLock rwlock = new ReentrantReadWriteLock();

    public DisposablePixmap(int width, int height, Pixmap.Format format) {
        this.pm = new Pixmap(width, height, format);
    }
    
    public void drawPixmap(DisposablePixmap pixmap, int srcx, int srcy, int srcWidth, int srcHeight, int dstx, int dsty, int dstWidth,
                                       int dstHeight) {
        Lock writeLock = rwlock.writeLock();
        writeLock.lock();
        try {
            pm.drawPixmap(pixmap.getPixmap(), srcx, srcy, srcWidth, srcHeight, dstx, dsty, dstWidth, dstHeight);
        } finally {
            writeLock.unlock();
        }
    }
    
    public void drawPixmap(DisposablePixmap pixmap, int x, int y) {
        Lock writeLock = rwlock.writeLock();
        writeLock.lock();
        try {
            pm.drawPixmap(pixmap.getPixmap(), x, y);
        } finally {
            writeLock.unlock();
        }
    }

    private Pixmap getPixmap() {
        Lock readLock = rwlock.readLock();
        readLock.lock();
        try {
            if (pm.isDisposed())
                return null;
            return pm;
        } finally {
            readLock.unlock();
        }
    }

    public void dispose() {
        Lock writeLock = rwlock.writeLock();
        writeLock.lock();
        try {
            pm.dispose();
        } finally {
            writeLock.unlock();
        }
    }

    public boolean isDisposed() {
        Lock readLock = rwlock.readLock();
        readLock.lock();
        try {
            return pm.isDisposed();
        } finally {
            readLock.unlock();
        }
    }

    public void setColor(Color color) {
        Lock writeLock = rwlock.writeLock();
        writeLock.lock();
        try {
            pm.setColor(color);
        } finally {
            writeLock.unlock();
        }
    }

    public void fill() {
        Lock writeLock = rwlock.writeLock();
        writeLock.lock();
        try {
            pm.fill();
        } finally {
            writeLock.unlock();
        }
    }
}
