package org.example.betty.domain.display.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.betty.common.util.SessionUtil;
import org.example.betty.domain.display.dto.Pixel;
import org.example.betty.domain.display.dto.PixelUpdateMessage;
import org.example.betty.domain.display.service.DisplayService;
import org.example.betty.exception.BusinessException;
import org.example.betty.exception.ErrorCode;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Map;
import java.util.Objects;

@Controller
@RequiredArgsConstructor
@Slf4j
public class DisplaySocketController {

    private final DisplayService displayService;
    private final SimpMessagingTemplate messagingTemplate;
    private final SessionUtil sessionUtil;

    @MessageMapping("/updatePixel")
    public void updatePixel(PixelUpdateMessage message, SimpMessageHeaderAccessor headerAccessor) {
        String walletAddress = authorizedWallet(headerAccessor, message.getGameId(), message.getTeamId());

        Pixel pixel = new Pixel(walletAddress, message.getColor());
        displayService.updatePixel(message.getGameId(), message.getTeamId(), message.getR(), message.getC(), pixel);
        message.setWalletAddress(walletAddress);

        String destination = "/topic/pixelUpdate/" + message.getGameId() + "/" + message.getTeamId();
        messagingTemplate.convertAndSend(destination, message);
    }

    @MessageMapping("/getBoard/{gameId}/{teamId}")
    @SendTo("/topic/board/{gameId}/{teamId}")
    public Pixel[][] getBoard(@DestinationVariable Long gameId, @DestinationVariable Long teamId,
                             SimpMessageHeaderAccessor headerAccessor) {
        authorizedWallet(headerAccessor, gameId, teamId);
        log.info("display 조회 요청 도착" + gameId + "," + teamId);
        return displayService.getDisplay(gameId, teamId);
    }

    private String authorizedWallet(SimpMessageHeaderAccessor headers, Long gameId, Long teamId) {
        Map<String, Object> attributes = headers.getSessionAttributes();
        if (attributes == null
                || !(attributes.get("authenticatedSession") instanceof SessionUtil.SessionIdentity identity)
                || !(attributes.get("displayGameId") instanceof Long)
                || !(attributes.get("displayTeamId") instanceof Long)
                || !Objects.equals(gameId, attributes.get("displayGameId"))
                || !Objects.equals(teamId, attributes.get("displayTeamId"))) {
            throw new BusinessException(ErrorCode.INVALID_WEBSOCKET_SESSION);
        }
        sessionUtil.validateSession(identity);
        return identity.walletAddress();
    }
}
