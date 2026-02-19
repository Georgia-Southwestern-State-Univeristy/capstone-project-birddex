package com.birddex.app;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * SearchCollectionFragment displays the user's collection of identified birds.
 * It fetches the bird images from Firestore and displays them in a 3x5 grid.
 */
public class SearchCollectionFragment extends Fragment {

    private static final String TAG = "SearchCollectionFragment";
    private RecyclerView rvCollection;
    private SimpleGridAdapter adapter;
    private final List<String> imageUrls = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_search_collection, container, false);

        rvCollection = v.findViewById(R.id.rvCollection);
        
        // Set up the grid layout manager with 3 columns.
        rvCollection.setLayoutManager(new GridLayoutManager(getContext(), 3));

        // Initialize the adapter with an empty list.
        adapter = new SimpleGridAdapter(imageUrls);
        rvCollection.setAdapter(adapter);

        // Fetch the user's collection from Firestore.
        fetchUserCollection();

        return v;
    }

    /**
     * Fetches up to 15 bird discoveries from the user's "collection" in Firestore.
     * Orders them by timestamp so the newest ones appear first.
     */
    private void fetchUserCollection() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .collection("collectionSlot")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(15) // Limit to 15 slots as requested.
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    imageUrls.clear();
                    
                    // Log the number of documents found.
                    Log.d(TAG, "Found " + queryDocumentSnapshots.size() + " documents in collection.");

                    // Extract image URLs from the documents.
                    for (QueryDocumentSnapshot document : queryDocumentSnapshots) {
                        // Log the full document data to inspect its contents.
                        Log.d(TAG, "Document data: " + document.getData());
                        String imageUrl = document.getString("imageUrl");
                        if (imageUrl != null) {
                            imageUrls.add(imageUrl);
                        }
                    }

                    // Fill the remaining slots with null to maintain a 3x5 grid look (up to 15).
                    while (imageUrls.size() < 15) {
                        imageUrls.add(null); 
                    }

                    adapter.notifyDataSetChanged();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error fetching collection", e);
                    Toast.makeText(getContext(), "Failed to load collection.", Toast.LENGTH_SHORT).show();
                });
    }
}
