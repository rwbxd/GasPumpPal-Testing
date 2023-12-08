package com.rwbdev.prototest

import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.RecyclerView
import com.google.protobuf.Timestamp
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

class FillupAdapter(
    private val fillups: MutableList<Fillup>
) : RecyclerView.Adapter<FillupAdapter.FillupViewHolder>() {
    class FillupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    constructor(): this(ArrayList())

    fun addFillup(fillup: Fillup) {
        fillups.add(0, fillup)
        notifyItemInserted(0)
    }

    fun removeFillup(index: Int) {
        fillups.removeAt(index)
        notifyItemRemoved(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FillupViewHolder {
        return FillupViewHolder(
            LayoutInflater.from(parent.context).inflate(
                R.layout.gas_record,
                parent,
                false
            )
        )
    }

    override fun getItemCount(): Int {
        return fillups.size
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onBindViewHolder(holder: FillupViewHolder, position: Int) {
        val curFillup = fillups[position]
        holder.itemView.apply{
            val Miles = findViewById<TextView>(R.id.FillupMiles)
            val Gallons = findViewById<TextView>(R.id.FillupGallons)
            val Cost = findViewById<TextView>(R.id.FillupCost)
            val Date = findViewById<TextView>(R.id.FillupDate)
            val Time = findViewById<TextView>(R.id.FillupTime)

            Miles.text = NumberFormat.getNumberInstance(Locale.US).format(curFillup.miles) + " miles"
            Gallons.text = String.format("%.3f", curFillup.gallons) + " gallons"
            Cost.text = "$" + String.format("%.2f", curFillup.cost)
            val date = timestampToDate(curFillup.time)
            val df = SimpleDateFormat("MM-dd-yyyy")
            Date.text = df.format(date)
            val tf = SimpleDateFormat("h:mm a")
            Time.text = tf.format(date)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun timestampToDate(timestamp: Timestamp): Date? {
        // Convert Timestamp to Instant
        val instant = Instant.ofEpochSecond(timestamp.seconds, timestamp.nanos.toLong())

        // Convert Instant to Date
        return Date.from(instant)
    }
}