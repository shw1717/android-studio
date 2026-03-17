package com.example.myapplication

// [안드로이드 및 파이어베이스 관련 import]
import android.Manifest
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.databinding.DialogSetTownBinding
import com.example.myapplication.databinding.FragmentWriteBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import org.json.JSONObject
import java.io.IOException
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import okhttp3.*
import java.net.URLEncoder

class WriteFragment<TextView> : Fragment() {

    // ViewBinding
    private var _binding: FragmentWriteBinding? = null
    private val binding get() = _binding!!

    // 파이어스토어 및 위치 관련
    private lateinit var firestore: FirebaseFirestore
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLocation: Location? = null

    // RecyclerView 및 페이징
    private lateinit var postAdapter: PostAdapter
    private lateinit var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private var lastVisibleDocument: DocumentSnapshot? = null
    private val postsPerPage = 10
    private var isLoading = false
    private var isLastPage = false

    // 반경 필터 관련
    private val radiusOptions = listOf(1, 3, 5)
    private var selectedRadiusKm = 1

    // FAB 상태
    private var isFabOpen = false

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }

    // ----------------------- 프래그먼트 뷰 생성 -----------------------
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWriteBinding.inflate(inflater, container, false)

        // 초기화
        swipeRefreshLayout = binding.swipeRefreshLayout
        firestore = FirebaseFirestore.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        getCurrentLocation()
        setupRadiusSpinner()

        // 새로고침 리스너
        swipeRefreshLayout.setOnRefreshListener {
            refreshPosts()
        }

        // 어댑터 설정
        postAdapter = PostAdapter(requireContext())
        binding.recyclerViewPosts.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = postAdapter

            // 무한 스크롤 리스너
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (isLastPage || isLoading) return
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val totalItemCount = layoutManager.itemCount
                    val lastVisibleItemPosition = layoutManager.findLastVisibleItemPosition()
                    Log.d("페이징", "스크롤 상태: $lastVisibleItemPosition / $totalItemCount")
                    if ((lastVisibleItemPosition + 3) >= totalItemCount) {
                        loadMorePosts()
                    }
                }
            })
        }

        // FAB 클릭
        binding.fabMain.setOnClickListener { toggleFab() }
        binding.fabWrite.setOnClickListener {
            val intent = Intent(requireContext(), BoardActivity::class.java)
            startActivity(intent)
        }
        binding.fabEraser.setOnClickListener {
            val intent = Intent(requireContext(), EditPostActivity::class.java)
            startActivity(intent)
        }

        // 거리 필터 적용 버튼
        binding.btnApplyRadius.setOnClickListener {
            if (currentLocation != null) {
                loadNearbyPosts(currentLocation!!.latitude, currentLocation!!.longitude, selectedRadiusKm)
            } else {
                Toast.makeText(requireContext(), "위치를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSetTown.setOnClickListener {
            val dialogBinding = DialogSetTownBinding.inflate(layoutInflater)
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@setOnClickListener

            // 현재 저장된 동네 보여주기
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .get()
                .addOnSuccessListener { doc ->
                    val myTown = doc.getString("myTown") ?: "설정되지 않음"
                    dialogBinding.currentTownText.text = "현재 내 동네: $myTown"
                }

            AlertDialog.Builder(requireContext())
                .setTitle("내 동네 설정")
                .setView(dialogBinding.root)
                .setPositiveButton("저장") { _, _ ->
                    val region = dialogBinding.inputTownEdit.text.toString().trim()
                    if (region.isNotEmpty()) {
                        // 🔹 주소 → 위도/경도 변환
                        geocodeAddress(region) { lat, lng ->
                            if (lat != null && lng != null) {
                                val data = mapOf(
                                    "myTown" to region,
                                    "latitude" to lat,
                                    "longitude" to lng
                                )
                                FirebaseFirestore.getInstance()
                                    .collection("users")
                                    .document(uid)
                                    .set(data, SetOptions.merge()) // 기존 필드 유지하면서 병합
                                    .addOnSuccessListener {
                                        requireActivity().runOnUiThread {
                                            Toast.makeText(requireContext(), "내 동네가 저장되었습니다.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .addOnFailureListener {
                                        requireActivity().runOnUiThread {
                                            Toast.makeText(requireContext(), "저장 실패", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                            } else {
                                requireActivity().runOnUiThread {
                                    Toast.makeText(requireContext(), "주소 변환 실패", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
                .setNegativeButton("취소", null)
                .show()
        }

        // 게시글 필터 버튼
        binding.filterLayout.setOnClickListener {
            showFilterBottomSheet()
        }

        loadInitialPosts()
        return binding.root
    }

    private fun showFilterBottomSheet() {
        val bottomSheetView = layoutInflater.inflate(R.layout.bottom_sheet_filter, null)
        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(bottomSheetView)

        val btnMyTown = bottomSheetView.findViewById<Button>(R.id.btnMyTown)
        val btnLogTown = bottomSheetView.findViewById<Button>(R.id.btnlogTown)
        val btnShowAll = bottomSheetView.findViewById<Button>(R.id.btnShowAll)

        // 🔹 btnMyTown
        btnMyTown?.setOnClickListener {
            currentLocation?.let {
                val lat = it.latitude
                val lng = it.longitude
                fetchRegionNameFromLocation(lat, lng) { regionName ->
                    if (regionName != null) {
                        postAdapter.submitList(emptyList())
                        loadPostsByRegion(regionName)
                        Toast.makeText(requireContext(), "현재 동네: $regionName", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "지역 정보를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            } ?: Toast.makeText(requireContext(), "위치 정보를 불러오고 있습니다.", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        // 🔹 btnlogTown
        btnLogTown?.setOnClickListener {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@setOnClickListener

            FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .get()
                .addOnSuccessListener { document ->
                    val myTown = document.getString("myTown")
                    if (!myTown.isNullOrEmpty()) {
                        loadPostsByMyTown(myTown)
                    } else {
                        Toast.makeText(requireContext(), "내 동네가 설정되지 않았습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(requireContext(), "내 동네 정보를 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
                }
            dialog.dismiss()
        }

        // 🔹 btnShowAll
        btnShowAll?.setOnClickListener {
            isLastPage = false
            isLoading = false
            lastVisibleDocument = null
            postAdapter.submitList(emptyList())
            loadInitialPosts()
            dialog.dismiss()
        }

        dialog.show()
    }

    // ----------------------- 위치 권한 및 현재 위치 요청 -----------------------
    private fun getCurrentLocation() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    currentLocation = location
                    if (location != null) {
                        fetchRegionNameFromLocation(location.latitude, location.longitude) { regionName ->
                            if (regionName != null) {
                                binding.textMyTown.text = regionName
                            } else {
                                binding.textMyTown.text = "지역 정보 없음"
                            }
                        }
                    } else {
                        Log.e("위치", "위치 정보를 가져올 수 없습니다.")
                    }
                }
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }





    // ----------------------- FAB 열고 닫기 애니메이션 -----------------------
    private fun toggleFab() {
        if (isFabOpen) {
            ObjectAnimator.ofFloat(binding.fabEraser, "translationY", 0f).start()
            ObjectAnimator.ofFloat(binding.fabWrite, "translationY", 0f).start()
            binding.fabMain.setImageResource(R.drawable.ic_plus)
        } else {
            ObjectAnimator.ofFloat(binding.fabEraser, "translationY", -200f).start()
            ObjectAnimator.ofFloat(binding.fabWrite, "translationY", -400f).start()
            binding.fabMain.setImageResource(R.drawable.baseline_clear)
        }
        isFabOpen = !isFabOpen
    }

    // ----------------------- Firestore에서 첫 게시글들 불러오기 -----------------------
    private fun loadInitialPosts() {
        isLoading = true
        isLastPage = false
        lastVisibleDocument = null

        firestore.collection("posts")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(postsPerPage.toLong())
            .get()
            .addOnSuccessListener { documents ->
                // 🔧 문서 ID를 Post 객체의 id 필드에 직접 넣음
                val posts = documents.map { doc ->
                    val post = doc.toObject(Post::class.java)
                    post.id = doc.id
                    post
                }

                postAdapter.submitList(posts)
                lastVisibleDocument = documents.documents.lastOrNull()
                isLastPage = documents.size() < postsPerPage
                isLoading = false
            }
            .addOnFailureListener {
                Log.e("WriteFragment", "Firestore 데이터 가져오기 실패", it)
                isLoading = false
            }
    }

    // ----------------------- 게시글 새로고침 -----------------------
    private fun refreshPosts() {
        isLastPage = false
        lastVisibleDocument = null
        swipeRefreshLayout.isRefreshing = true

        firestore.collection("posts")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(postsPerPage.toLong())
            .get()
            .addOnSuccessListener { documents ->
                val posts = documents.map { doc ->
                    val post = doc.toObject(Post::class.java)
                    post.id = doc.id  // 🔧 문서 ID 수동 설정
                    post
                }

                postAdapter.submitList(posts)
                lastVisibleDocument = documents.documents.lastOrNull()
                isLastPage = documents.size() < postsPerPage
                swipeRefreshLayout.isRefreshing = false
            }
            .addOnFailureListener {
                Log.e("WriteFragment", "Firestore 새로고침 실패", it)
                swipeRefreshLayout.isRefreshing = false
            }
    }

    // ----------------------- 추가 게시글 불러오기 (무한 스크롤용) -----------------------
    private fun loadMorePosts() {
        if (lastVisibleDocument == null || isLastPage) return

        isLoading = true

        firestore.collection("posts")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .startAfter(lastVisibleDocument!!)
            .limit(postsPerPage.toLong())
            .get()
            .addOnSuccessListener { documents ->
                val currentList = postAdapter.currentList.toMutableList()


                val newPosts = documents.map { doc ->
                    val post = doc.toObject(Post::class.java)
                    post.id = doc.id  // <- 핵심 수정!
                    post
                }

                val uniquePosts = newPosts.filterNot { newPost ->
                    currentList.any { it.id == newPost.id }
                }

                currentList.addAll(uniquePosts)
                postAdapter.submitList(currentList)

                lastVisibleDocument = documents.documents.lastOrNull()
                isLastPage = documents.size() < postsPerPage
                isLoading = false
            }
            .addOnFailureListener {
                Log.e("WriteFragment", "Firestore 추가 데이터 가져오기 실패", it)
                isLoading = false
            }
    }


    // ----------------------- 반경 거리 선택 스피너 설정 -----------------------
    private fun setupRadiusSpinner() {
        val spinner = binding.spinnerRadius
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, listOf("1km", "3km", "5km"))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedRadiusKm = radiusOptions[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ----------------------- 반경 내 게시글 불러오기 -----------------------
    private fun loadNearbyPosts(lat: Double, lng: Double, radiusKm: Int) {
        firestore.collection("posts")
            .get()
            .addOnSuccessListener { result ->
                val filteredPosts = result.mapNotNull { doc ->
                    val postLat = doc.getDouble("latitude") ?: return@mapNotNull null
                    val postLng = doc.getDouble("longitude") ?: return@mapNotNull null
                    val distance = calculateDistance(lat, lng, postLat, postLng)
                    if (distance <= radiusKm) {
                        val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()
                        Post(
                            id = doc.id,
                            title = doc.getString("title") ?: "",
                            content = doc.getString("content") ?: "",
                            timestamp = timestamp,
                            latitude = postLat,
                            longitude = postLng,
                            authorName = doc.getString("authorName") ?: "익명",
                            profileImage = doc.getString("profileImage") ?: "profile1",
                            userId = doc.getString("userId") ?: "",
                        )
                    } else null
                }
                val sortedPosts = filteredPosts.sortedByDescending { it.timestamp }
                postAdapter.submitList(sortedPosts)
            }
    }

    // ----------------------- 거리 계산 (Haversine 공식) -----------------------
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    // ----------------------- 현재 동네 기능 -----------------------
    private fun fetchRegionNameFromLocation(
        lat: Double,
        lng: Double,
        callback: (String?) -> Unit
    ) {
        val url = "https://maps.apigw.ntruss.com/map-reversegeocode/v2/gc" +
                "?coords=$lng,$lat&orders=admcode&output=json"

        val request = okhttp3.Request.Builder()
            .url(url)
            .addHeader("X-NCP-APIGW-API-KEY-ID", "jv40jxc6a8")
            .addHeader("X-NCP-APIGW-API-KEY", "ddjRlhl1zePYUcq90z3pk8oIBWdQvB5dhxKnOSVh")
            .build()

        val client = okhttp3.OkHttpClient()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e("MyTown", "지역 정보 요청 실패", e)
                if (isAdded && _binding != null) {
                    requireActivity().runOnUiThread {
                        binding.textMyTown.text = "서울특별시 도봉구"
                        callback("서울특별시 도봉구")
                    }
                } else {
                    callback("서울특별시 도봉구")
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val responseBody = response.body?.string() ?: return callback(null)
                try {
                    val json = JSONObject(responseBody)
                    val results = json.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val region = results.getJSONObject(0).getJSONObject("region")
                        val area1 = region.getJSONObject("area1").getString("name")
                        val area2 = region.getJSONObject("area2").getString("name")
                        val townName = "$area1 $area2"

                        if (isAdded && _binding != null) {
                            requireActivity().runOnUiThread {
                                binding.textMyTown.text = townName
                                callback(townName)
                            }
                        } else {
                            callback(townName)
                        }

                    } else {
                        if (isAdded && _binding != null) {
                            requireActivity().runOnUiThread {
                                binding.textMyTown.text = "서울특별시 도봉구"
                                callback("서울특별시 도봉구")
                            }
                        } else {
                            callback("서울특별시 도봉구")
                        }
                        Log.e("MyTown", "결과 없음 → 기본값 설정")
                    }
                } catch (e: Exception) {
                    Log.e("MyTown", "JSON 파싱 오류", e)
                    callback(null)
                }
            }
        })
    }
    // ----------------------- 현재동네 게시글 불러오기 기능-----------------------
    private fun loadPostsByRegion(regionName: String) {
        firestore.collection("posts")
            .whereEqualTo("region", regionName)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { documents ->
                val posts = documents.mapNotNull { doc ->
                    try {
                        val post = doc.toObject(Post::class.java)
                        post.id = doc.id

                        // 필수 필드 누락되었으면 null 반환해서 제외
                        if (post.title.isNullOrBlank() || post.timestamp == 0L) {
                            null
                        } else {
                            post
                        }
                    } catch (e: Exception) {
                        Log.e("loadPostsByRegion", "게시글 변환 실패: ${e.message}")
                        null
                    }
                }

                postAdapter.submitList(posts)
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "게시글 로딩에 실패했습니다.", Toast.LENGTH_SHORT).show()
            }
    }
    // ----------------------- 내동네 게시글 불러오기 기능-----------------------
    private fun loadPostsByMyTown(regionName: String) {
        firestore.collection("posts")
            .whereEqualTo("region", regionName)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { documents ->
                val posts = documents.mapNotNull { doc ->
                    try {
                        val post = doc.toObject(Post::class.java).apply {
                            id = doc.id
                        }
                        if (post.title.isNullOrBlank() || post.timestamp == 0L) null else post
                    } catch (e: Exception) {
                        Log.e("loadPostsByRegion", "게시글 변환 실패: ${e.message}")
                        null
                    }
                }
                postAdapter.submitList(posts)
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "게시글 로딩에 실패했습니다.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun geocodeAddress(address: String, callback: (Double?, Double?) -> Unit) {
        val clientId = "jv40jxc6a8"
        val clientSecret = "ddjRlhl1zePYUcq90z3pk8oIBWdQvB5dhxKnOSVh"
        val url = "https://maps.apigw.ntruss.com/map-geocode/v2/geocode?query=${URLEncoder.encode(address, "UTF-8")}"

        val request = Request.Builder()
            .url(url)
            .addHeader("X-NCP-APIGW-API-KEY-ID", clientId)
            .addHeader("X-NCP-APIGW-API-KEY", clientSecret)
            .build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null, null)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.body?.string()
                if (result != null) {
                    val json = JSONObject(result)
                    val addresses = json.optJSONArray("addresses")
                    if (addresses != null && addresses.length() > 0) {
                        val obj = addresses.getJSONObject(0)
                        val lat = obj.getDouble("y")
                        val lng = obj.getDouble("x")
                        callback(lat, lng)
                    } else {
                        callback(null, null)
                    }
                } else {
                    callback(null, null)
                }
            }
        })
    }



    // ----------------------- 뷰 해제 -----------------------
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
