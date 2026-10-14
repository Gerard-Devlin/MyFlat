package dev.myflat.app;

import android.app.SearchManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import com.google.zxing.*;
import com.google.zxing.client.result.*;
import com.google.zxing.common.HybridBinarizer;
import java.util.*;

/** Local QR decoding. Actions are offered only after the user taps the detected result. */
final class CameraQrDetector {
  private CameraQrDetector() {}

  static final class Detection {
    final String text, title, description;
    final ParsedResult parsed;

    Detection(Result result) {
      text = result.getText();
      parsed = ResultParser.parseResult(result);
      switch (parsed.getType()) {
        case URI:
          title = "网站";
          description = "打开" + ((URIParsedResult) parsed).getURI();
          break;
        case TEL:
          title = "电话";
          description = "拨打" + ((TelParsedResult) parsed).getNumber();
          break;
        case EMAIL_ADDRESS:
          title = "电子邮件";
          description = "发送电子邮件";
          break;
        case SMS:
          title = "信息";
          description = "发送信息";
          break;
        case GEO:
          title = "地点";
          description = "在地图上查看更多";
          break;
        case ADDRESSBOOK:
          title = "联系人";
          description = "保存联系人";
          break;
        case CALENDAR:
          title = "日程";
          description = "将日程表添加到日历";
          break;
        default:
          title = parsed.getType() == ParsedResultType.TEXT ? "文本" : "其他";
          description = "搜索“" + text + "”";
      }
    }

    Intent intent() {
      switch (parsed.getType()) {
        case URI:
          Uri uri = Uri.parse(((URIParsedResult) parsed).getURI());
          if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
            return new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
          return search();
        case TEL:
          return new Intent(Intent.ACTION_DIAL, Uri.parse(((TelParsedResult) parsed).getTelURI()));
        case EMAIL_ADDRESS:
          EmailAddressParsedResult email = (EmailAddressParsedResult) parsed;
          Intent mail = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
          mail.putExtra(Intent.EXTRA_EMAIL, email.getTos());
          mail.putExtra(Intent.EXTRA_CC, email.getCCs());
          mail.putExtra(Intent.EXTRA_BCC, email.getBCCs());
          mail.putExtra(Intent.EXTRA_SUBJECT, email.getSubject());
          mail.putExtra(Intent.EXTRA_TEXT, email.getBody());
          return mail;
        case SMS:
          SMSParsedResult sms = (SMSParsedResult) parsed;
          return new Intent(Intent.ACTION_SENDTO, Uri.parse(sms.getSMSURI()))
              .putExtra("sms_body", sms.getBody());
        case GEO:
          return new Intent(Intent.ACTION_VIEW, Uri.parse(((GeoParsedResult) parsed).getGeoURI()));
        case ADDRESSBOOK:
          AddressBookParsedResult contact = (AddressBookParsedResult) parsed;
          Intent insert =
              new Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
                  .setType(ContactsContract.Contacts.CONTENT_TYPE);
          insert.putExtra(ContactsContract.Intents.Insert.NAME, first(contact.getNames()));
          insert.putExtra(ContactsContract.Intents.Insert.PHONE, first(contact.getPhoneNumbers()));
          insert.putExtra(ContactsContract.Intents.Insert.EMAIL, first(contact.getEmails()));
          insert.putExtra(ContactsContract.Intents.Insert.COMPANY, contact.getOrg());
          insert.putExtra(ContactsContract.Intents.Insert.NOTES, contact.getNote());
          return insert;
        case CALENDAR:
          CalendarParsedResult event = (CalendarParsedResult) parsed;
          Intent calendar = new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI);
          calendar.putExtra(CalendarContract.Events.TITLE, event.getSummary());
          calendar.putExtra(CalendarContract.Events.EVENT_LOCATION, event.getLocation());
          calendar.putExtra(CalendarContract.Events.DESCRIPTION, event.getDescription());
          calendar.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.getStartTimestamp());
          if (event.getEndTimestamp() >= 0)
            calendar.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.getEndTimestamp());
          calendar.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.isStartAllDay());
          return calendar;
        default:
          return search();
      }
    }

    private Intent search() {
      return new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text);
    }

    private static String first(String[] values) {
      return values == null || values.length == 0 ? null : values[0];
    }
  }

  static Detection detect(Bitmap image) {
    int width = image.getWidth(), height = image.getHeight();
    int[] pixels = new int[width * height];
    image.getPixels(pixels, 0, width, 0, 0, width, height);
    MultiFormatReader reader = new MultiFormatReader();
    Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
    hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
    hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
    hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
    reader.setHints(hints);
    try {
      RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
      try {
        return new Detection(reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source))));
      } catch (NotFoundException notFound) {
        reader.reset();
        return new Detection(
            reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source.invert()))));
      }
    } catch (NotFoundException | IllegalArgumentException ignored) {
      return null;
    } finally {
      reader.reset();
    }
  }
}
