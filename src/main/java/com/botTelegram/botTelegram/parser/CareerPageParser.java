package com.botTelegram.botTelegram.parser;

import com.botTelegram.botTelegram.domain.Vacancy;
import org.openqa.selenium.WebDriver;

import java.util.Map;

public interface CareerPageParser {
    String getSiteName();
    Map<String, String> parseIdList(WebDriver driver);
    Vacancy parseDetails(WebDriver driver, String externalId, String url);
}