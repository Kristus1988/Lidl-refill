package com.example.lidlrefill;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class HumanBehavior {
    private final WebDriver driver;
    private final Actions actions;
    private final Random random;
    private final JavascriptExecutor js;
    
    public HumanBehavior(WebDriver driver) {
        this.driver = driver;
        this.actions = new Actions(driver);
        this.random = new Random();
        this.js = (JavascriptExecutor) driver;
    }
    
    public void humanPause() {
        int ms = ThreadLocalRandom.current().nextInt(300, 1200);
        sleep(ms);
    }
    
    public void humanTypingPause() {
        int ms = ThreadLocalRandom.current().nextInt(60, 250);
        sleep(ms);
    }
    
    public void humanThinkingPause() {
        int ms = ThreadLocalRandom.current().nextInt(800, 3000);
        sleep(ms);
    }
    
    public void humanReadingPause() {
        int ms = ThreadLocalRandom.current().nextInt(2000, 6000);
        sleep(ms);
    }
    
    public void humanShortPause() {
        int ms = ThreadLocalRandom.current().nextInt(100, 500);
        sleep(ms);
    }
    
    public void humanMoveTo(WebElement element) {
        try {
            js.executeScript(
                "arguments[0].scrollIntoView({behavior: 'smooth', block: 'center'});",
                element
            );
            humanShortPause();
            
            int offsetX = random.nextInt(30) - 15;
            int offsetY = random.nextInt(30) - 15;
            int steps = random.nextInt(4) + 3;
            
            for (int i = 0; i < steps; i++) {
                int delay = random.nextInt(80) + 40;
                actions.moveByOffset(offsetX / steps, offsetY / steps).perform();
                sleep(delay);
            }
            
            sleep(random.nextInt(100) + 50);
            actions.moveToElement(element).perform();
            sleep(random.nextInt(150) + 50);
            
        } catch (Exception e) {
            try {
                actions.moveToElement(element).perform();
            } catch (Exception ex) {
                // Ignorieren
            }
        }
    }
    
    public void humanClick(WebElement element) {
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            wait.until(ExpectedConditions.elementToBeClickable(element));
            
            js.executeScript(
                "arguments[0].scrollIntoView({behavior: 'smooth', block: 'center'});",
                element
            );
            humanShortPause();
            humanMoveTo(element);
            
            if (random.nextBoolean()) {
                sleep(random.nextInt(300) + 200);
            }
            
            actions.click().perform();
            sleep(random.nextInt(300) + 150);
            
        } catch (Exception e) {
            try {
                js.executeScript("arguments[0].click();", element);
            } catch (Exception ex) {
                // Ignorieren
            }
        }
    }
    
    public void humanClickSafe(WebElement element) {
        try {
            for (int i = 0; i < 3; i++) {
                try {
                    if (element.isDisplayed() && element.isEnabled()) {
                        humanClick(element);
                        return;
                    }
                } catch (Exception e) {
                    sleep(500);
                }
            }
            js.executeScript("arguments[0].click();", element);
        } catch (Exception e) {
            // Ignorieren
        }
    }
    
    public void humanType(WebElement element, String text) {
        try {
            humanClick(element);
            element.clear();
            humanShortPause();
            
            for (char c : text.toCharArray()) {
                element.sendKeys(String.valueOf(c));
                sleep(ThreadLocalRandom.current().nextInt(60, 250));
                
                if (random.nextInt(12) == 0) {
                    sleep(random.nextInt(400) + 200);
                }
            }
            
            sleep(random.nextInt(300) + 150);
            
        } catch (Exception e) {
            element.sendKeys(text);
        }
    }
    
    public void randomScroll() {
        if (random.nextBoolean()) {
            int pixels = ThreadLocalRandom.current().nextInt(100, 600);
            String direction = random.nextBoolean() ? "" : "-";
            js.executeScript("window.scrollBy(0, " + direction + pixels + ");");
            sleep(random.nextInt(300) + 100);
        }
    }
    
    public void randomMouseMovement() {
        if (random.nextInt(4) == 0) {
            int x = ThreadLocalRandom.current().nextInt(100, 800);
            int y = ThreadLocalRandom.current().nextInt(100, 1500);
            actions.moveByOffset(x, y).perform();
            sleep(random.nextInt(200) + 100);
            actions.moveByOffset(-x/2, -y/2).perform();
        }
    }
    
    public void randomHover() {
        if (random.nextInt(3) == 0) {
            try {
                List<WebElement> elements = driver.findElements(By.tagName("a"));
                if (!elements.isEmpty()) {
                    WebElement randomElement = elements.get(random.nextInt(elements.size()));
                    if (randomElement.isDisplayed()) {
                        actions.moveToElement(randomElement).perform();
                        sleep(random.nextInt(500) + 300);
                        actions.moveByOffset(0, 50).perform();
                    }
                }
            } catch (Exception e) {
                // Ignorieren
            }
        }
    }
    
    public void randomPageInteraction() {
        int action = random.nextInt(4);
        switch (action) {
            case 0:
                randomScroll();
                break;
            case 1:
                randomMouseMovement();
                break;
            case 2:
                randomHover();
                break;
            case 3:
                try {
                    js.executeScript("window.scrollTo(0, 0);");
                    sleep(500);
                    js.executeScript("window.scrollTo(0, document.body.scrollHeight);");
                    sleep(500);
                    js.executeScript("window.scrollTo(0, 300);");
                } catch (Exception e) {
                    // Ignorieren
                }
                break;
        }
    }
    
    public void waitForPageLoad() {
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
            wait.until(webDriver -> ((JavascriptExecutor) webDriver)
                .executeScript("return document.readyState").equals("complete"));
        } catch (Exception e) {
            // Ignorieren
        }
    }
    
    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
