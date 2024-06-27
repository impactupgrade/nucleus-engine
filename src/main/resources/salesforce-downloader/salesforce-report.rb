#!/usr/bin/ruby

require 'net/http'
require 'net/https'
require 'rexml/document'
require 'date'
require 'net/smtp'
require 'yaml'
require 'fileutils'

include REXML

class Result
  def initialize(xmldoc)
    @xmldoc = xmldoc
  end

  def server_url
    @server_url ||= XPath.first(@xmldoc, '//result/serverUrl/text()')
  end

  def session_id
    @session_id ||= XPath.first(@xmldoc, '//result/sessionId/text()')
  end

  def org_id
    @org_id ||= XPath.first(@xmldoc, '//result/userInfo/organizationId/text()')
  end
end

class SfError < Exception
  attr_accessor :resp

  def initialize(resp)
    @resp = resp
  end

  def inspect
    puts resp.body
  end
  alias_method :to_s, :inspect
end

### Helpers ###

def http(host=ENV["SFDC_URL"], port=443)
    h = Net::HTTP.new(host, port)
    h.use_ssl = true
    h
end

def headers(login)
  {
    'Cookie'         => "oid=#{login.org_id.value}; sid=#{login.session_id.value}",
    'X-SFDC-Session' => login.session_id.value
  }
end

def file_name(url=nil)
  qm_index = url.index('?') - 1
  file_name = url[1..qm_index] + ".csv"
  puts file_name
  file_name
end

### Salesforce interactions ###

def login
  puts "Logging in..."
  path = '/services/Soap/u/48.0'

  pwd_token_encoded = ENV["SFDC_PASSWORD"]
  pwd_token_encoded = pwd_token_encoded.gsub(/&(?!amp;)/,'&amp;')
  pwd_token_encoded = pwd_token_encoded.gsub(/</,'&lt;')
  pwd_token_encoded = pwd_token_encoded.gsub(/>/,'&gt;')

  inital_data = <<-EOF
<?xml version="1.0" encoding="utf-8" ?>
<env:Envelope xmlns:xsd="http://www.w3.org/2001/XMLSchema"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xmlns:env="http://schemas.xmlsoap.org/soap/envelope/">
  <env:Body>
    <n1:login xmlns:n1="urn:partner.soap.sforce.com">
      <n1:username>#{ENV["SFDC_USERNAME"]}</n1:username>
      <n1:password>#{pwd_token_encoded}</n1:password>
    </n1:login>
  </env:Body>
</env:Envelope>
  EOF

  puts inital_data

  initial_headers = {
    'Content-Type' => 'text/xml; charset=UTF-8',
    'SOAPAction' => 'login'
  }

  resp = http('login.salesforce.com').post(path, inital_data, initial_headers)

  if resp.code == '200'
    xmldoc = Document.new(resp.body)
    puts "Login OK!"
    return Result.new(xmldoc)
  else
    raise SfError.new(resp)
  end
end

def get_urls(login)
  puts "Getting urls..."
  urls = ["/" + ENV["SFDC_REPORT_ID"] + "?isdtp=p1&export=1&enc=UTF-8&xf=csv"]
end

def download_file(login, url, fdir, fn)
  puts "Downloading #{fn}..."
  f = open("#{fdir}/#{fn}", "wb")
  begin
    http.request_get(url, headers(login)) do |resp|
      resp.read_body do |segment|
        f.write(segment)
      end
      puts "\nFinished downloading #{fn}!"
    end
  ensure
    f.close()
  end
end

begin
  puts "Downloading reports..."

  result = login

  urls = get_urls(result)
  puts "Report urls:"
  puts urls
  puts ''

  reports_download_dir = "report-salesforce"
  unless File.directory?(reports_download_dir)
    FileUtils.mkdir_p(reports_download_dir)
  end

  urls.each do |url|
    puts "Generating filename for report url..."
    fn = file_name(url)
    file_path = "#{reports_download_dir}/#{fn}"
    puts "File path: #{file_path}"

    retry_count = 0
    begin
      puts "Working on: #{url}"
      download_file(result, url, reports_download_dir, fn)
    rescue Exception => e
      if retry_count < 3
        retry_count += 1
        puts "Error: #{e}"
        puts "Retrying (retry_count of 3)..."
        retry
      end
    end
  end
  puts "Done!"
end




