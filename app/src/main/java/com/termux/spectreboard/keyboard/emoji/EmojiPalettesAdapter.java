/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.termux.spectreboard.keyboard.emoji;

import com.termux.spectreboard.latin.utils.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.termux.spectreboard.keyboard.Keyboard;
import com.termux.spectreboard.latin.R;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

final class EmojiPalettesAdapter extends RecyclerView.Adapter<EmojiPalettesAdapter.ViewHolder>{
    private static final String TAG = EmojiPalettesAdapter.class.getSimpleName();
    private static final boolean DEBUG_PAGER = false;

    private final int mCategoryId;
    private final EmojiViewCallback mEmojiViewCallback;
    private final EmojiCategory mEmojiCategory;

    public EmojiPalettesAdapter(final EmojiCategory emojiCategory, int categoryId, final EmojiViewCallback emojiViewCallback) {
        mEmojiCategory = emojiCategory;
        mCategoryId = categoryId;
        mEmojiViewCallback = emojiViewCallback;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        final EmojiPageKeyboardView keyboardView = (EmojiPageKeyboardView)inflater.inflate(
                R.layout.emoji_keyboard_page, parent, false);
        keyboardView.setEmojiViewCallback(mEmojiViewCallback);
        return new ViewHolder(keyboardView);
    }

    @Override
    public void onBindViewHolder(@NonNull EmojiPalettesAdapter.ViewHolder holder, int position) {
        if (DEBUG_PAGER) {
            Log.d(TAG, "instantiate item: " + position);
        }

        final Keyboard keyboard = mEmojiCategory.getKeyboardFromAdapterPosition(mCategoryId, position);
        holder.getKeyboardView().setKeyboard(keyboard);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
        holder.getKeyboardView().releaseCurrentKey(false);
        holder.getKeyboardView().deallocateMemory();
    }

    @Override
    public int getItemCount() {
        return mEmojiCategory.getCategoryPageCount(mCategoryId);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final EmojiPageKeyboardView customView;

        public ViewHolder(View v) {
            super(v);
            customView = (EmojiPageKeyboardView) v;
        }

        public EmojiPageKeyboardView getKeyboardView() {
            return customView;
        }
    }
}

