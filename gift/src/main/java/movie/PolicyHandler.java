package movie;

import movie.config.kafka.KafkaProcessor;

import java.util.Random;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

@Service
public class PolicyHandler{

    @Autowired
    GiftRepository giftRepository;

    @StreamListener(KafkaProcessor.INPUT)
    public void onStringEventListener(@Payload String eventString){

    }

    @StreamListener(KafkaProcessor.INPUT)
    public void wheneverPrinted_(@Payload Printed printed){

        if(printed.isMe()){
            System.out.println("======================================");
            System.out.println("##### listener  : " + printed.toJson());
            System.out.println("======================================");

            Gift gift = new Gift();
            gift.setBookingId(printed.getId());

            Random random = new Random();
            Integer randomValue = random.nextInt(3);
            switch (randomValue) {	
                case 0:		
                    gift.setName("Americano");
                    gift.setGiftCode("G000");
                    break;
                case 1:		
                    gift.setName("CafeLatte");
                    gift.setGiftCode("G001");
                    break; 
                case 2:
                    gift.setName("CafeMocha");
                    gift.setGiftCode("G002");
                    break;
                case 3:
                    gift.setName("Cappuccino");
                    gift.setGiftCode("G003");
                    break;    
                default:
                    gift.setName("Americano");
                    gift.setGiftCode("G000");
            };
  
        
        


            gift.setStatus("GiftApplied");

            giftRepository.save(gift);
        }
    }

}
