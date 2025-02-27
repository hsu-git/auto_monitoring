import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

public class App {
    public static void main(String[] args) {
        Monitoring monitoring = new Monitoring();
        monitoring.getNews(System.getenv("KEYWORD"), 10, 1, SortType.date);
    }
}

enum SortType {
    sim("sim"), date("date");

    final String value;

    SortType(String value) {
        this.value = value;
    }
}

class Monitoring {
    private final Logger logger;

    public Monitoring() {
        logger = Logger.getLogger(Monitoring.class.getName());
        logger.setLevel(Level.SEVERE);
        logger.info("Monitoring 객체 생성");
    }

    // 1. 검색어를 통해서 최근 10개의 뉴스를 받아올게요
    public void getNews(String keyword, int display, int start, SortType sort) {
        String imageLink = "";
        try {
            String response = getDataFromAPI("news.json", keyword, display, start, sort);
            String[] tmp = response.split("title\":\"");
            // 0번째를 제외하곤 데이터
            String[] result = new String[display];

            // 🟢 뉴스 링크 배열 추가
            String[] newsLinks = new String[display];

            for (int i = 1; i < tmp.length; i++) {
                result[i - 1] = tmp[i].split("\",")[0];

                // 🟢 뉴스 링크 추출
                newsLinks[i - 1] = tmp[i].split("link\":\"")[1].split("\",")[0];
            }
            logger.info(Arrays.toString(result));

            // 파일 저장
            File file = new File("%d_%s.txt".formatted(new Date().getTime(), keyword));
            if (!file.exists()) {
                logger.info(file.createNewFile() ? "신규 생성" : "이미 있음");
            }
            try (FileWriter fileWriter = new FileWriter(file)) {
                for (String s : result) {
                    fileWriter.write(s + "\n");
                }
                logger.info("기록 성공");
            } // flush 및 close.
            logger.info("제목 목록 생성 완료");

            // 이미지 다운로드
            String imageResponse = getDataFromAPI("image", keyword, display, start, SortType.sim);
            imageLink = imageResponse
                    .split("link\":\"")[1].split("\",")[0]
                    .split("\\?")[0]
                    .replace("\\", "");
            logger.info(imageLink);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageLink))
                    .build();
            String[] tmp2 = imageLink.split("\\.");
            Path path = Path.of("%d_%s.%s".formatted(
                    new Date().getTime(), keyword, tmp2[tmp2.length - 1]));
            HttpClient.newHttpClient().send(request,
                    HttpResponse.BodyHandlers.ofFile(path));

            // 🟢 Together API 사용하여 뉴스 요약 추가
            String summary = getSummaryFromLLM(result);
            logger.info("요약 결과: " + summary);

            // 🟢 Slack으로 요약 + 이미지 + 뉴스 링크 전송 추가
            sendToSlack(keyword, summary, imageLink, newsLinks);

        } catch (Exception e) {
            logger.severe(e.getMessage());
        }
    }

    // 네이버 API 요청
    private String getDataFromAPI(String path, String keyword, int display, int start, SortType sort) throws Exception {
//        String url = "https://openapi.naver.com/v1/search/news.json";
        String url = "https://openapi.naver.com/v1/search/%s".formatted(path);
        String params = "query=%s&display=%d&start=%d&sort=%s".formatted(
                keyword, display, start, sort.value
        );
        HttpClient client = HttpClient.newHttpClient(); // 클라이언트
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url + "?" + params))
                .GET()
                .header("X-Naver-Client-Id", System.getenv("NAVER_CLIENT_ID"))
                .header("X-Naver-Client-Secret", System.getenv("NAVER_CLIENT_SECRET"))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            // http 요청을 했을 때 잘 왔는지 보는 것
            logger.info(Integer.toString(response.statusCode()));
            logger.info(response.body());
            // split하든 나중에 GSON, Jackson
            return response.body();
        } catch (Exception e) {
            logger.severe(e.getMessage());
            throw new Exception("연결 에러");
        }
    }

    // 🟢 Together API 사용하여 LLM 뉴스 요약 추가
    private String getSummaryFromLLM(String[] newsTitles) {
        try {
            String apiKey = System.getenv("OPEN_API_KEY");
            String apiUrl = System.getenv("OPEN_API_URL");
            String model = System.getenv("OPEN_API_MODEL");
            String promptTemplate = System.getenv("LLM_PROMPT");
            // String prompt = promptTemplate.replace("{news}", String.join("\n", newsTitles));

            // 🟢 🔍 프롬프트 치환 과정 확인 (디버깅용)
            // String prompt = promptTemplate.replace("{news}", String.join("\n", newsTitles));
            // 🟢 `newsTitles`가 비어 있을 경우 대비하여 기본 요청 메시지를 추가
            String newsContent = newsTitles.length > 0 ? String.join("\n", newsTitles) : "최근 뉴스 기사 목록을 요약해줘.";
            String prompt = promptTemplate.replace("{news}", newsContent);
            logger.info("📝 LLM 프롬프트: " + prompt);

            // 🟢 Together API 요청 로그
            logger.info("🟢 Together API 호출 시작...");
            logger.info("🔗 API URL: " + apiUrl);
            logger.info("🔑 API Key 사용 여부: " + (apiKey != null ? "✅ 있음" : "❌ 없음"));
            logger.info("📝 모델: " + model);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{ \"model\": \"" + model + "\", \"prompt\": \"" + prompt + "\", \"max_tokens\": 200 }"
                    ))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            logger.info("🟢 LLM 응답 코드: " + response.statusCode());
            logger.info("🟢 LLM 응답 본문: " + response.body());

            if (response.statusCode() != 200) {
                logger.severe("🔴 LLM API 요청 실패! 응답 코드: " + response.statusCode());
                return "요약 실패 (API 오류)";
            }

            return response.body().split("\"text\":\"")[1].split("\"")[0]; // 응답에서 요약 부분 추출
        } catch (Exception e) {
            logger.severe("🔴 LLM 요청 오류: " + e.getMessage());
            return "요약 실패";
        }
    }

    // 🟢 Slack 메시지 개선 (이미지 & 뉴스 링크 추가)
    private void sendToSlack(String keyword, String summary, String imageUrl, String[] newsLinks) {
        try {
            String webhookUrl = System.getenv("SLACK_WEBHOOK_URL");
            String title = System.getenv("SLACK_WEBHOOK_TITLE");

            // 🔗 뉴스 링크들 정리
            StringBuilder linksMessage = new StringBuilder();
            for (String link : newsLinks) {
                linksMessage.append("🔗 ").append(link).append("\n");
            }

            // 이미지 포함 메시지 (Slack의 `attachments` 사용)
            String message = "{"
                    + "\"text\": \"" + title + "\\n🔔 *" + keyword + "* 뉴스 요약:\\n" + summary + "\","
                    + "\"attachments\": ["
                    + "{ \"text\": \"" + linksMessage.toString() + "\", \"image_url\": \"" + imageUrl + "\" }"
                    + "]"
                    + "}";

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(message))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            logger.info("Slack 전송 결과: " + response.body());

        } catch (Exception e) {
            logger.severe("Slack 전송 오류: " + e.getMessage());
        }
    }
}