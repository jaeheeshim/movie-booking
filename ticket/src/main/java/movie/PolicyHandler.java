package movie;

import movie.config.kafka.KafkaProcessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

@Service
public class PolicyHandler{
    @StreamListener(KafkaProcessor.INPUT)
    public void onStringEventListener(@Payload String eventString){

    }
    
    @Autowired
    TicketRepository ticketRepository;


    @StreamListener(KafkaProcessor.INPUT)
    public void wheneverBooked_(@Payload Booked booked){

        if(booked.isMe()){
            Ticket ticket = new Ticket();
            ticket.setBookingId(booked.getId());
            ticket.setMovieName(booked.getMovieName());
            ticket.setQty(booked.getQty());
            ticket.setSeat(booked.getSeat());
            ticket.setStatus("Ticket Created");
            ticketRepository.save(ticket);
            System.out.println("##### listener  : " + booked.toJson());
        }
    }

}
